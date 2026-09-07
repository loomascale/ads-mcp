package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDetailDto;
import com.loomascale.googleads.client.dto.GoogleAssetLinkDto;
import com.loomascale.googleads.client.dto.GoogleAssetLinkSpec;
import com.loomascale.googleads.client.dto.GoogleMediaSlot;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.googleads.mcp.service.GoogleAssetMediaService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.McpToolException;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// The images, logos, video and call-to-action of a Performance Max asset group.
//
// A separate tool from google_update_asset_group_assets on purpose, not for schema tidiness.
// This is the only Google tool that dereferences a URL the caller supplied, and keeping it a
// separate bean keeps SafeImageFetcher out of the text tool's dependency graph — so the text
// tool provably cannot make an outbound request, rather than merely appearing not to. It also
// earns a daily cap, because every call costs bandwidth and permanently creates Asset rows
// that the API cannot delete; capping text edits would be friction for nothing.
//
// How an image diff is possible at all: Google exposes no way to compare an image already in
// the account against bytes on disk, and asset.image_asset.full_size.url is a Google-hosted
// copy. But Google deduplicates image assets by CONTENT, so creating the asset IS the
// content lookup — the resource name that comes back is the existing asset's when the account
// already holds those bytes. An unchanged image therefore keeps its link and its history, at
// the cost of re-uploading bytes Google already has. The caveat is in the description: the
// dedupe is on exact bytes, so a re-encoded copy of the same picture is a different asset.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateAssetGroupMediaTool implements AdsTool {

  private static final String YOUTUBE_VIDEO = "YOUTUBE_VIDEO";
  private static final String CALL_TO_ACTION = "CALL_TO_ACTION_SELECTION";
  private static final String AUTOMATED = "AUTOMATED";
  private static final int MAX_VIDEOS = 15;

  // The 11-character id from a watch URL, not the URL itself.
  private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

  // Each call downloads files and permanently creates Asset rows, so it is rate-limited the
  // way the campaign-creating tools are. A static final int, because the cross-cutting tests
  // build this bean with null dependencies and still call description().
  private static final int MAX_MEDIA_EDITS_PER_DAY = 20;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final GoogleAssetMediaService media;
  private final McpWriteAuditService audit;
  private final ProductBranding branding;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_asset_group_media";
  }

  @Override
  public String description() {
    return "Replace the images, logos, YouTube video or call-to-action of a Performance Max asset"
        + " group. Images are given as public https URLs, which this server downloads and uploads"
        + " to Google; videos are given as YouTube video ids, not URLs. Each list REPLACES the"
        + " whole slot — send every image you want the slot to have — and a slot you omit is left"
        + " untouched. An image whose bytes Google already holds keeps its existing asset and its"
        + " performance history, so passing the same URLs again changes nothing; note that the"
        + " match is on exact bytes, so a re-encoded copy of the same picture counts as a new"
        + " image. Google's shapes are enforced before anything is uploaded: marketing images"
        + " 1.91:1, square 1:1, portrait 4:5, logos 1:1, landscape logos 4:1. For headlines and"
        + " descriptions use google_update_asset_group_assets.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "asset_group_id",
        "string",
        "Asset group to change, as reported by google_list_asset_groups.");
    for (GoogleMediaSlot slot : GoogleAssetMediaService.IMAGE_SLOTS) {
      McpSchemas.stringArrayProp(
          schema,
          slot.argument(),
          "Complete new set of public https image URLs for this slot: "
              + (slot.min() == 0 ? "optional, " : "at least " + slot.min() + ", ")
              + "at most "
              + slot.max()
              + ", each "
              + slot.aspectRatioLabel()
              + " and at least "
              + slot.minWidth()
              + "x"
              + slot.minHeight()
              + " pixels, PNG or JPEG under "
              + GoogleAssetMediaService.MAX_IMAGE_BYTES / 1024
              + " KB. Omit to leave this slot alone.");
    }
    McpSchemas.stringArrayProp(
        schema,
        "youtube_video_ids",
        "Complete new set of YouTube video ids — the 11-character id from the watch URL, not the"
            + " URL. At most "
            + MAX_VIDEOS
            + ". Omit to leave the videos alone.");
    ObjectNode callToAction =
        McpSchemas.prop(
            schema,
            "call_to_action",
            "string",
            "The button text Google shows. AUTOMATED removes the asset and lets Google choose,"
                + " which is usually the better answer unless a specific action matters.");
    McpSchemas.enumValues(callToAction, GoogleAssetMediaService.CALL_TO_ACTIONS);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "asset_group_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "assetGroupId", "string", "Asset group that was changed.");
    McpSchemas.prop(
        schema,
        "changed",
        "boolean",
        "False when every asset you passed was already linked, in which case no link was"
            + " altered.");
    ObjectNode field =
        McpSchemas.objectArrayProp(schema, "fields", "One entry per slot this call touched.");
    McpSchemas.prop(field, "fieldType", "string", "Which slot, e.g. MARKETING_IMAGE.");
    McpSchemas.stringArrayProp(field, "addedAssetIds", "Assets newly linked to the slot.");
    McpSchemas.stringArrayProp(field, "removedAssetIds", "Assets unlinked from the slot.");
    McpSchemas.stringArrayProp(
        field, "keptAssetIds", "Assets that were already linked and kept their history.");
    McpSchemas.prop(field, "countAfter", "integer", "How many assets fill the slot now.");
    McpSchemas.nullableProp(schema, "assetGroupName", "string", "Name of the asset group.");
    McpSchemas.nullableProp(schema, "campaignId", "string", "Campaign it belongs to.");
    McpSchemas.nullableProp(schema, "campaignName", "string", "Name of that campaign.");
    McpSchemas.nullableProp(
        schema, "callToAction", "string", "Call to action now in effect, if this call set one.");
    McpSchemas.nullableProp(schema, "adStrengthBefore", "string", "Ad strength before the change.");
    McpSchemas.required(schema, "assetGroupId", "changed", "fields");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: a slot's previous images or video are unlinked and Google cannot restore
    // the link, and there is no per-link pause.
    //
    // Idempotent, with one honest caveat: the lists are absolute values and Google
    // deduplicates an asset by content, so the same URLs resolve to the same asset ids and a
    // second identical call relinks nothing. But a URL is a pointer, not the content — if the
    // bytes behind it change, the same arguments produce a different asset. MCP's idempotency
    // hint is about repeating the same arguments, and identical arguments over a stable URL
    // are identical bytes, so true is the right answer with that caveat stated.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("asset_group_id")) {
      throw new McpToolException("asset_group_id is required.");
    }
    String assetGroupId = args.get("asset_group_id").asText();

    // The cap has to fire before any download and before any Asset is created, because those
    // are precisely the costs it exists to bound.
    if (audit.countToday(userId, name()) >= MAX_MEDIA_EDITS_PER_DAY) {
      throw new McpToolException(
          "You have already made "
              + MAX_MEDIA_EDITS_PER_DAY
              + " media edits today, which is the daily limit — each one downloads"
              + " files and permanently adds assets to your Google Ads account. Try again"
              + " tomorrow, or make the remaining changes in Google Ads.");
    }

    // Everything local first: a bad URL or a wrong id costs no download and no Google call.
    Map<GoogleMediaSlot, List<String>> imageUrls = new LinkedHashMap<>();
    int totalUrls = 0;
    for (GoogleMediaSlot slot : GoogleAssetMediaService.IMAGE_SLOTS) {
      if (!args.has(slot.argument())) {
        continue;
      }
      List<String> urls = parseUrls(args.get(slot.argument()), slot);
      totalUrls += urls.size();
      imageUrls.put(slot, urls);
    }
    if (totalUrls > GoogleAssetMediaService.MAX_URLS_PER_CALL) {
      throw new McpToolException(
          "At most "
              + GoogleAssetMediaService.MAX_URLS_PER_CALL
              + " image URLs per call (got "
              + totalUrls
              + "), because each one is downloaded and uploaded. Split the change across"
              + " several calls.");
    }
    List<String> videoIds = args.has("youtube_video_ids") ? parseVideoIds(args) : null;
    String callToAction = null;
    if (args.hasNonNull("call_to_action")) {
      callToAction = args.get("call_to_action").asText().trim().toUpperCase();
      if (!GoogleAssetMediaService.CALL_TO_ACTIONS.contains(callToAction)) {
        throw new McpToolException(
            "call_to_action must be one of: "
                + String.join(", ", GoogleAssetMediaService.CALL_TO_ACTIONS)
                + ".");
      }
    }
    if (imageUrls.isEmpty() && videoIds == null && callToAction == null) {
      throw new McpToolException(
          "Provide one of the image slots, youtube_video_ids, call_to_action, or a combination —"
              + " there is nothing to change.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    Set<String> fieldTypes = new LinkedHashSet<>();
    imageUrls.keySet().forEach(slot -> fieldTypes.add(slot.fieldType()));
    if (videoIds != null) {
      fieldTypes.add(YOUTUBE_VIDEO);
    }
    if (callToAction != null) {
      fieldTypes.add(CALL_TO_ACTION);
    }

    GoogleAssetGroupDetailDto group =
        support.requireAssetGroup(connection, token, customerId, loginCustomerId, assetGroupId);
    support.requireAssetGroupIsEditable(group, fieldTypes);

    List<GoogleAssetLinkDto> links =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listAssetGroupAssetLinks(token, customerId, loginCustomerId, assetGroupId));
    if (links.size() >= GoogleAdsApiClient.MAX_ASSET_LINK_ROWS) {
      throw new McpToolException(
          "Asset group "
              + assetGroupId
              + " reports at least "
              + links.size()
              + " linked assets, which is more than this tool reads in one go. Diffing against a"
              + " partial view could unlink the wrong assets, so the edit was not attempted.");
    }

    Map<String, List<String>> added = new LinkedHashMap<>();
    Map<String, List<String>> removed = new LinkedHashMap<>();
    Map<String, List<String>> kept = new LinkedHashMap<>();
    Map<String, Integer> countAfter = new LinkedHashMap<>();
    List<GoogleAssetLinkSpec> toLink = new ArrayList<>();
    List<GoogleAssetLinkSpec> toUnlink = new ArrayList<>();

    // Videos and the call to action are diffed first, because they need no download: a call
    // that only touches them and changes nothing costs no bandwidth at all.
    if (videoIds != null) {
      List<GoogleAssetLinkDto> current = linksOf(links, YOUTUBE_VIDEO);
      List<String> keptIds = new ArrayList<>();
      List<String> addedIds = new ArrayList<>();
      List<String> newVideoIds = new ArrayList<>();
      for (String videoId : videoIds) {
        GoogleAssetLinkDto existing =
            current.stream()
                .filter(link -> videoId.equals(link.youtubeVideoId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
          keptIds.add(existing.assetId());
        } else {
          newVideoIds.add(videoId);
        }
      }
      List<String> removedIds = new ArrayList<>();
      for (GoogleAssetLinkDto link : current) {
        if (!videoIds.contains(link.youtubeVideoId())) {
          removedIds.add(link.assetId());
          toUnlink.add(
              new GoogleAssetLinkSpec(link.assetResourceName(), link.assetId(), YOUTUBE_VIDEO));
        }
      }
      for (String videoId : newVideoIds) {
        String resourceName =
            adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .createYoutubeVideoAsset(
                            token,
                            customerId,
                            loginCustomerId,
                            branding.productName() + " video " + videoId,
                            videoId));
        addedIds.add(lastSegment(resourceName));
        toLink.add(new GoogleAssetLinkSpec(resourceName, null, YOUTUBE_VIDEO));
      }
      record(added, removed, kept, countAfter, YOUTUBE_VIDEO, addedIds, removedIds, keptIds);
    }

    if (callToAction != null) {
      String wantedAction = callToAction;
      List<GoogleAssetLinkDto> current = linksOf(links, CALL_TO_ACTION);
      boolean alreadySet =
          current.stream().anyMatch(link -> wantedAction.equals(link.callToAction()));
      List<String> addedIds = new ArrayList<>();
      List<String> removedIds = new ArrayList<>();
      List<String> keptIds = new ArrayList<>();
      if (AUTOMATED.equals(callToAction)) {
        // AUTOMATED is not a value Google stores; it means "no call-to-action asset".
        current.forEach(
            link -> {
              removedIds.add(link.assetId());
              toUnlink.add(
                  new GoogleAssetLinkSpec(
                      link.assetResourceName(), link.assetId(), CALL_TO_ACTION));
            });
      } else if (alreadySet) {
        current.stream()
            .filter(link -> wantedAction.equals(link.callToAction()))
            .forEach(link -> keptIds.add(link.assetId()));
      } else {
        current.forEach(
            link -> {
              removedIds.add(link.assetId());
              toUnlink.add(
                  new GoogleAssetLinkSpec(
                      link.assetResourceName(), link.assetId(), CALL_TO_ACTION));
            });
        String resourceName =
            adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .createCallToActionAsset(token, customerId, loginCustomerId, wantedAction));
        addedIds.add(lastSegment(resourceName));
        toLink.add(new GoogleAssetLinkSpec(resourceName, null, CALL_TO_ACTION));
      }
      record(added, removed, kept, countAfter, CALL_TO_ACTION, addedIds, removedIds, keptIds);
    }

    // Images last, because this is where the bandwidth goes.
    for (Map.Entry<GoogleMediaSlot, List<String>> entry : imageUrls.entrySet()) {
      GoogleMediaSlot slot = entry.getKey();
      List<GoogleAssetLinkDto> current = linksOf(links, slot.fieldType());
      Set<String> requestedResourceNames = new LinkedHashSet<>();
      for (String url : entry.getValue()) {
        // Creating the asset IS the content lookup: identical bytes come back as the asset
        // the account already holds.
        requestedResourceNames.add(
            media.uploadImage(connection, token, customerId, loginCustomerId, slot, url));
      }
      Set<String> requestedAssetIds = new LinkedHashSet<>();
      requestedResourceNames.forEach(name -> requestedAssetIds.add(lastSegment(name)));

      List<String> keptIds = new ArrayList<>();
      List<String> addedIds = new ArrayList<>();
      for (String resourceName : requestedResourceNames) {
        String assetId = lastSegment(resourceName);
        if (current.stream().anyMatch(link -> assetId.equals(link.assetId()))) {
          keptIds.add(assetId);
        } else {
          addedIds.add(assetId);
          toLink.add(new GoogleAssetLinkSpec(resourceName, null, slot.fieldType()));
        }
      }
      List<String> removedIds = new ArrayList<>();
      for (GoogleAssetLinkDto link : current) {
        if (!requestedAssetIds.contains(link.assetId())) {
          removedIds.add(link.assetId());
          toUnlink.add(
              new GoogleAssetLinkSpec(link.assetResourceName(), link.assetId(), slot.fieldType()));
        }
      }
      record(added, removed, kept, countAfter, slot.fieldType(), addedIds, removedIds, keptIds);
    }

    String argsSummary =
        "assetGroup="
            + assetGroupId
            + ";link="
            + toLink.size()
            + ";unlink="
            + toUnlink.size()
            + ";fields="
            + String.join("/", countAfter.keySet());
    log.debug("google_update_asset_group_media user={} {}", userId, argsSummary);

    if (toLink.isEmpty() && toUnlink.isEmpty()) {
      return ToolResult.ok(
          "Asset group "
              + assetGroupId
              + " already has exactly those assets, so no link was changed.",
          structured(group, assetGroupId, false, added, removed, kept, countAfter, callToAction));
    }

    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .mutateAssetGroupAssetLinks(
                    token, customerId, loginCustomerId, assetGroupId, toLink, toUnlink);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_asset_group_media</b>\nUser: "
              + userId
              + "\nAsset group: "
              + assetGroupId
              + " ("
              + group.name()
              + ")\nCampaign: "
              + group.campaignName()
              + "\nLinked: "
              + toLink.size()
              + "\nUnlinked: "
              + toUnlink.size());
      return ToolResult.ok(
          text(group, assetGroupId, added, removed, kept, countAfter),
          structured(group, assetGroupId, true, added, removed, kept, countAfter, callToAction));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, false, e.getMessage());
      String hint = support.assetErrorHint(e.getMessage());
      if (!hint.isEmpty() && e instanceof McpToolException) {
        throw new McpToolException(e.getMessage() + " " + hint);
      }
      throw e;
    }
  }

  private List<String> parseUrls(JsonNode node, GoogleMediaSlot slot) {
    if (!node.isArray()) {
      throw new McpToolException(slot.argument() + " must be an array of https URLs.");
    }
    List<String> urls = new ArrayList<>();
    for (JsonNode entry : node) {
      String url = support.requireHttpUrl(entry.asText("").trim(), slot.argument());
      if (urls.contains(url)) {
        throw new McpToolException(
            slot.argument() + " lists " + url + " twice. Each entry has to be different.");
      }
      urls.add(url);
    }
    if (urls.size() < slot.min() || urls.size() > slot.max()) {
      throw new McpToolException(
          slot.argument()
              + " must contain "
              + slot.min()
              + "-"
              + slot.max()
              + " URLs (got "
              + urls.size()
              + ")."
              + (slot.min() > 0
                  ? " Google requires at least " + slot.min() + " for this slot."
                  : ""));
    }
    return urls;
  }

  private List<String> parseVideoIds(JsonNode args) {
    JsonNode node = args.get("youtube_video_ids");
    if (!node.isArray()) {
      throw new McpToolException("youtube_video_ids must be an array of YouTube video ids.");
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode entry : node) {
      String id = entry.asText("").trim();
      if (!YOUTUBE_ID.matcher(id).matches()) {
        // Pasting the watch URL is the common mistake, so name it.
        throw new McpToolException(
            "\""
                + id
                + "\" is not a YouTube video id. Pass the 11-character id from the watch URL"
                + " (the v= part), not the URL itself.");
      }
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    if (ids.size() > MAX_VIDEOS) {
      throw new McpToolException(
          "An asset group takes at most " + MAX_VIDEOS + " videos (got " + ids.size() + ").");
    }
    return ids;
  }

  private List<GoogleAssetLinkDto> linksOf(List<GoogleAssetLinkDto> links, String fieldType) {
    return links.stream().filter(link -> fieldType.equals(link.fieldType())).toList();
  }

  private void record(
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept,
      Map<String, Integer> countAfter,
      String fieldType,
      List<String> addedIds,
      List<String> removedIds,
      List<String> keptIds) {
    added.put(fieldType, addedIds);
    removed.put(fieldType, removedIds);
    kept.put(fieldType, keptIds);
    countAfter.put(fieldType, addedIds.size() + keptIds.size());
  }

  private String lastSegment(String resourceName) {
    return resourceName == null ? null : resourceName.substring(resourceName.lastIndexOf('/') + 1);
  }

  private ObjectNode structured(
      GoogleAssetGroupDetailDto group,
      String assetGroupId,
      boolean changed,
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept,
      Map<String, Integer> countAfter,
      String callToAction) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("assetGroupId", assetGroupId);
    structured.put("changed", changed);
    ArrayNode fields = structured.putArray("fields");
    for (String fieldType : countAfter.keySet()) {
      ObjectNode node = fields.addObject();
      node.put("fieldType", fieldType);
      added.get(fieldType).forEach(node.putArray("addedAssetIds")::add);
      removed.get(fieldType).forEach(node.putArray("removedAssetIds")::add);
      kept.get(fieldType).forEach(node.putArray("keptAssetIds")::add);
      node.put("countAfter", countAfter.get(fieldType));
    }
    structured.put("assetGroupName", group.name());
    structured.put("campaignId", group.campaignId());
    structured.put("campaignName", group.campaignName());
    structured.put("callToAction", callToAction);
    structured.put("adStrengthBefore", group.adStrength());
    return structured;
  }

  private String text(
      GoogleAssetGroupDetailDto group,
      String assetGroupId,
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept,
      Map<String, Integer> countAfter) {
    StringBuilder text = new StringBuilder();
    text.append("Updated the media of asset group ")
        .append(group.name() == null ? assetGroupId : group.name())
        .append(" in campaign ")
        .append(group.campaignName())
        .append("\n");
    for (String fieldType : countAfter.keySet()) {
      text.append(fieldType)
          .append(": ")
          .append(countAfter.get(fieldType))
          .append(" now (")
          .append(added.get(fieldType).size())
          .append(" added, ")
          .append(removed.get(fieldType).size())
          .append(" removed, ")
          .append(kept.get(fieldType).size())
          .append(" kept)\n");
    }
    text.append(
        "Google reviews new assets and recomputes ad strength, so read the group back with"
            + " google_list_asset_groups in a while to see the effect.");
    return text.toString();
  }
}
