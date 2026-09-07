package com.loomascale.googleads.mcp.service;

import com.loomascale.googleads.client.dto.GoogleMediaSlot;
import com.loomascale.mcp.security.FetchedCreativeImage;
import com.loomascale.mcp.security.ImageDimensionReader;
import com.loomascale.mcp.security.ImageDimensions;
import com.loomascale.mcp.security.OutboundUrlRejectedException;
import com.loomascale.mcp.security.SafeImageFetcher;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.tool.McpToolException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Turns the image URLs a Performance Max media edit receives into asset resource names Google
// already holds.
//
// The fetch itself is SafeImageFetcher's, unchanged: https only, no credentials in the URL,
// ports 80 and 443 only, every private and link-local address range refused, redirects
// followed manually with full re-validation of each hop, a byte cap counted against bytes
// actually read, a wall-clock deadline inside the read loop, and the content type sniffed
// from magic bytes rather than trusted from the header. AdImageUploadService is the Meta
// equivalent and the call-site template.
//
// One deliberate divergence from that template: a transient download failure there falls back
// to handing Meta the picture URL, and here it must throw. An asset group cannot reference a
// URL, so a fallback would silently produce a group with a missing image.
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAssetMediaService {

  // Google's own cap is 5120 KB. Enforced here rather than by tightening
  // mcp.image-upload.max-bytes, whose 8 MB default the Meta path depends on.
  public static final int MAX_IMAGE_BYTES = 5_242_880;

  // ASPECT_RATIO_NOT_ALLOWED has undocumented crop tolerance, so a tight check refuses
  // images Google accepts.
  private static final double RATIO_TOLERANCE = 0.03;

  // One outbound fetch per URL, so this bounds bandwidth as well as blast radius.
  public static final int MAX_URLS_PER_CALL = 10;

  public static final List<GoogleMediaSlot> IMAGE_SLOTS =
      List.of(
          new GoogleMediaSlot(
              "MARKETING_IMAGE", "marketing_images", 1, 20, 1.91, "1.91:1", 600, 314),
          new GoogleMediaSlot(
              "SQUARE_MARKETING_IMAGE", "square_marketing_images", 1, 20, 1.0, "1:1", 300, 300),
          new GoogleMediaSlot(
              "PORTRAIT_MARKETING_IMAGE", "portrait_marketing_images", 0, 20, 0.8, "4:5", 480, 600),
          new GoogleMediaSlot("LOGO", "logos", 1, 5, 1.0, "1:1", 128, 128),
          new GoogleMediaSlot("LANDSCAPE_LOGO", "landscape_logos", 0, 20, 4.0, "4:1", 512, 128));

  // Google's CallToActionType values, plus AUTOMATED which means "let Google choose".
  public static final List<String> CALL_TO_ACTIONS =
      List.of(
          "AUTOMATED",
          "APPLY_NOW",
          "BOOK_NOW",
          "BUY_NOW",
          "CONTACT_US",
          "DONATE_NOW",
          "DOWNLOAD",
          "GET_QUOTE",
          "LEARN_MORE",
          "ORDER_NOW",
          "PLAY_NOW",
          "SEE_MORE",
          "SHOP_NOW",
          "SIGN_UP",
          "START_NOW",
          "SUBSCRIBE",
          "VISIT_SITE",
          "WATCH_NOW");

  private static final Map<String, GoogleMediaSlot> BY_FIELD_TYPE =
      IMAGE_SLOTS.stream()
          .collect(Collectors.toMap(GoogleMediaSlot::fieldType, Function.identity()));

  private final SafeImageFetcher imageFetcher;
  private final ImageDimensionReader dimensionReader;
  private final GoogleAdsService adsService;
  private final ProductBranding branding;

  public static Optional<GoogleMediaSlot> slotFor(String fieldType) {
    return Optional.ofNullable(BY_FIELD_TYPE.get(fieldType));
  }

  // Downloads one URL, checks it against the slot's requirements, and creates the asset.
  // Every check happens before the create, so a rejected image never leaves an Asset behind
  // — Google has no way to delete one.
  public String uploadImage(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      GoogleMediaSlot slot,
      String url) {
    FetchedCreativeImage image = fetch(slot, url);
    if (image.bytes().length > MAX_IMAGE_BYTES) {
      throw new McpToolException(
          "The image at "
              + url
              + " is "
              + image.bytes().length / 1024
              + " KB, and Google's limit for a Performance Max image is "
              + MAX_IMAGE_BYTES / 1024
              + " KB. Compress it or use a smaller version.");
    }
    ImageDimensions dimensions =
        dimensionReader
            .read(image.bytes())
            .orElseThrow(
                () ->
                    new McpToolException(
                        "The file at "
                            + url
                            + " does not look like a readable PNG or JPEG image, so its size"
                            + " could not be checked."));
    requireShape(slot, url, dimensions);

    // The name is derived from the content hash, so identical bytes always carry an
    // identical name and land on Google's dedupe path instead of DUPLICATE_ASSET_NAME.
    String assetName =
        branding.productName()
            + " "
            + slot.fieldType()
            + " "
            + dimensions
            + " "
            + contentHash(image.bytes());
    return adsService.call(
        connection,
        () ->
            adsService
                .client()
                .createImageAsset(token, customerId, loginCustomerId, assetName, image.bytes()));
  }

  private FetchedCreativeImage fetch(GoogleMediaSlot slot, String url) {
    try {
      return imageFetcher.fetch(url);
    } catch (OutboundUrlRejectedException e) {
      // A permanent refusal, and its message already says which rule the URL broke.
      throw new McpToolException(e.getMessage());
    } catch (IOException e) {
      // Unlike the Meta path there is no fallback: an asset group cannot reference a URL, so
      // continuing would ship a group with a missing image.
      throw new McpToolException(
          "Could not download the "
              + slot.argument()
              + " image at "
              + url
              + ": "
              + e.getMessage()
              + " Nothing was changed. Check the URL is publicly reachable and try again.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpToolException(
          "Downloading the image at " + url + " was interrupted. Nothing was changed.");
    }
  }

  private void requireShape(GoogleMediaSlot slot, String url, ImageDimensions dimensions) {
    if (dimensions.width() < slot.minWidth() || dimensions.height() < slot.minHeight()) {
      throw new McpToolException(
          "The image at "
              + url
              + " is "
              + dimensions
              + ", and Google needs at least "
              + slot.minWidth()
              + "x"
              + slot.minHeight()
              + " for "
              + slot.argument()
              + ".");
    }
    double ratio = dimensions.aspectRatio();
    if (Math.abs(ratio / slot.aspectRatio() - 1) > RATIO_TOLERANCE) {
      throw new McpToolException(
          "The image at "
              + url
              + " is "
              + dimensions
              + ", and "
              + slot.argument()
              + " needs a "
              + slot.aspectRatioLabel()
              + " image. Crop it to that shape and try again.");
    }
  }

  private String contentHash(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes)).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the platform, so this cannot happen.
      throw new IllegalStateException(e);
    }
  }
}
