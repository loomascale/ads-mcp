package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdDto;
import com.loomascale.googleads.client.dto.GoogleAdTextAssetDto;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.McpToolException;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Rewrites the copy of a responsive search ad that is already running. Until this tool
// existed the surface could propose better headlines and read the current ones, but the
// only way to apply them was to retype them in Google Ads or to build a parallel
// campaign — the expensive answer to a cheap question.
//
// The ad is edited in place: same ad id, same performance history. Google puts an edited
// ad back into policy review, and repeated fields are replaced wholesale, so a call
// carries the complete new list of whatever it changes.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateAdTool implements AdsTool {

  private static final String RESPONSIVE_SEARCH_AD = "RESPONSIVE_SEARCH_AD";

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_ad";
  }

  @Override
  public String description() {
    return "Rewrite the copy of an existing responsive search ad: its headlines, descriptions,"
        + " landing page and display paths. The ad keeps its id and performance history, and Google"
        + " puts it back into policy review after the edit. Each list you pass REPLACES the whole"
        + " list — send every headline you want the ad to have, not just the new ones — and a field"
        + " you omit is left untouched. Read the current copy first with google_list_ads. Only"
        + " responsive search ads can be edited this way; for other ad types create a new ad.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "ad_id", "string", "Ad to rewrite, as reported by google_list_ads.");
    ObjectNode headlines =
        McpSchemas.objectArrayProp(
            schema,
            "headlines",
            "Complete new set of headlines, "
                + GoogleAdsEditSupport.MIN_HEADLINES
                + "-"
                + GoogleAdsEditSupport.MAX_HEADLINES
                + " entries of at most "
                + GoogleAdsEditSupport.MAX_HEADLINE_LENGTH
                + " characters. An entry may be a plain string or an object with text and pinned."
                + " Omit to leave the headlines alone.");
    McpSchemas.prop(headlines, "text", "string", "Headline text.");
    ObjectNode headlinePin =
        McpSchemas.prop(
            headlines,
            "pinned",
            "string",
            "Slot to pin this headline to. Omit to let Google rotate it, which is usually better.");
    McpSchemas.enumValues(headlinePin, GoogleAdsEditSupport.HEADLINE_PINS);
    ObjectNode descriptions =
        McpSchemas.objectArrayProp(
            schema,
            "descriptions",
            "Complete new set of descriptions, "
                + GoogleAdsEditSupport.MIN_DESCRIPTIONS
                + "-"
                + GoogleAdsEditSupport.MAX_DESCRIPTIONS
                + " entries of at most "
                + GoogleAdsEditSupport.MAX_DESCRIPTION_LENGTH
                + " characters. Omit to leave the descriptions alone.");
    McpSchemas.prop(descriptions, "text", "string", "Description text.");
    ObjectNode descriptionPin =
        McpSchemas.prop(descriptions, "pinned", "string", "Slot to pin this description to.");
    McpSchemas.enumValues(descriptionPin, GoogleAdsEditSupport.DESCRIPTION_PINS);
    McpSchemas.prop(
        schema,
        "final_url",
        "string",
        "New landing page the ad clicks through to, an http(s) URL.");
    McpSchemas.prop(
        schema,
        "path1",
        "string",
        "First display path segment shown after the domain, at most "
            + GoogleAdsEditSupport.MAX_PATH_LENGTH
            + " characters. Pass an empty string to remove it.");
    McpSchemas.prop(schema, "path2", "string", "Second display path segment. Requires path1.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "ad_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "adId", "string", "Ad that was rewritten — unchanged by the edit.");
    McpSchemas.prop(schema, "adGroupId", "string", "Ad group it belongs to.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign it belongs to.");
    ObjectNode headline =
        McpSchemas.objectArrayProp(schema, "headlines", "Headlines now in effect.");
    McpSchemas.nullableProp(headline, "text", "string", "Headline text.");
    McpSchemas.nullableProp(headline, "pinned", "string", "Slot it is pinned to, if any.");
    ObjectNode description =
        McpSchemas.objectArrayProp(schema, "descriptions", "Descriptions now in effect.");
    McpSchemas.nullableProp(description, "text", "string", "Description text.");
    McpSchemas.nullableProp(description, "pinned", "string", "Slot it is pinned to, if any.");
    McpSchemas.stringArrayProp(schema, "finalUrls", "Landing pages now in effect.");
    McpSchemas.nullableProp(schema, "path1", "string", "First display path now in effect.");
    McpSchemas.nullableProp(schema, "path2", "string", "Second display path now in effect.");
    McpSchemas.nullableProp(
        schema,
        "previousApprovalStatus",
        "string",
        "Policy verdict the ad carried before the edit. Google reviews an edited ad again, so"
            + " expect it to read UNDER_REVIEW for a while.");
    McpSchemas.required(schema, "adId", "adGroupId", "campaignId");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: the previous copy is overwritten and this tool cannot bring it back,
    // and the ad re-enters review, so it can stop serving for a while. Idempotent: it
    // sets absolute values, so a repeat call leaves the ad in the same state.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("ad_id")) {
      throw new McpToolException("ad_id is required.");
    }
    String adId = args.get("ad_id").asText();
    boolean changesCopy =
        args.has("headlines")
            || args.has("descriptions")
            || args.hasNonNull("final_url")
            || args.has("path1")
            || args.has("path2");
    if (!changesCopy) {
      throw new McpToolException(
          "Provide headlines, descriptions, final_url, path1, path2, or any combination — there is"
              + " nothing to change.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleAdDto ad = requireAd(connection, token, customerId, loginCustomerId, adId);
    if (!RESPONSIVE_SEARCH_AD.equals(ad.type())) {
      throw new McpToolException(
          "Ad "
              + adId
              + " is a "
              + ad.type()
              + ", and Google cannot rewrite the text of that ad type in place. Create a new ad"
              + " instead.");
    }

    List<GoogleAdTextAssetDto> headlines =
        args.has("headlines")
            ? support.parseAdAssets(
                args.get("headlines"),
                "headlines",
                GoogleAdsEditSupport.MIN_HEADLINES,
                GoogleAdsEditSupport.MAX_HEADLINES,
                GoogleAdsEditSupport.MAX_HEADLINE_LENGTH,
                GoogleAdsEditSupport.HEADLINE_PINS)
            : null;
    List<GoogleAdTextAssetDto> descriptions =
        args.has("descriptions")
            ? support.parseAdAssets(
                args.get("descriptions"),
                "descriptions",
                GoogleAdsEditSupport.MIN_DESCRIPTIONS,
                GoogleAdsEditSupport.MAX_DESCRIPTIONS,
                GoogleAdsEditSupport.MAX_DESCRIPTION_LENGTH,
                GoogleAdsEditSupport.DESCRIPTION_PINS)
            : null;
    String finalUrl =
        args.hasNonNull("final_url")
            ? support.requireHttpUrl(args.get("final_url").asText().trim(), "final_url")
            : null;
    String path1 = path(args, "path1");
    String path2 = path(args, "path2");
    // Google rejects a second display path with no first one, whether the first is being
    // set now or was already there.
    String effectivePath1 = path1 != null ? path1 : ad.path1();
    if (path2 != null && !path2.isBlank() && (effectivePath1 == null || effectivePath1.isBlank())) {
      throw new McpToolException("path2 needs path1 — set path1 as well, or drop path2.");
    }

    String argsSummary =
        "ad="
            + adId
            + ";headlines="
            + (headlines == null ? "unchanged" : headlines.size())
            + ";descriptions="
            + (descriptions == null ? "unchanged" : descriptions.size())
            + ";finalUrl="
            + finalUrl
            + ";path1="
            + path1
            + ";path2="
            + path2;
    log.debug("google_update_ad user={} {}", userId, argsSummary);
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateResponsiveSearchAd(
                    token,
                    customerId,
                    loginCustomerId,
                    adId,
                    headlines,
                    descriptions,
                    finalUrl,
                    path1,
                    path2);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, adId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_ad</b>\nUser: "
              + userId
              + "\nAd: "
              + adId
              + " (campaign "
              + ad.campaignId()
              + ")\nHeadlines: "
              + (headlines == null ? "unchanged" : summary(headlines))
              + "\nDescriptions: "
              + (descriptions == null ? "unchanged" : summary(descriptions))
              + "\nFinal URL: "
              + (finalUrl == null ? "unchanged" : finalUrl));

      List<GoogleAdTextAssetDto> effectiveHeadlines =
          headlines == null ? ad.headlines() : headlines;
      List<GoogleAdTextAssetDto> effectiveDescriptions =
          descriptions == null ? ad.descriptions() : descriptions;
      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("adId", adId);
      structured.put("adGroupId", ad.adGroupId());
      structured.put("campaignId", ad.campaignId());
      writeAssets(structured.putArray("headlines"), effectiveHeadlines);
      writeAssets(structured.putArray("descriptions"), effectiveDescriptions);
      ArrayNode urls = structured.putArray("finalUrls");
      if (finalUrl != null) {
        urls.add(finalUrl);
      } else {
        ad.finalUrls().forEach(urls::add);
      }
      structured.put("path1", path1 != null ? path1 : ad.path1());
      structured.put("path2", path2 != null ? path2 : ad.path2());
      structured.put("previousApprovalStatus", ad.approvalStatus());

      return ToolResult.ok(
          text(
              ad,
              adId,
              headlines,
              descriptions,
              finalUrl,
              effectiveHeadlines,
              effectiveDescriptions),
          structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, adId, false, e.getMessage());
      throw e;
    }
  }

  private GoogleAdDto requireAd(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String adId) {
    return adsService
        .call(
            connection,
            () -> adsService.client().listAds(token, customerId, loginCustomerId, null, null, adId))
        .stream()
        .filter(candidate -> adId.equals(candidate.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new McpToolException(
                    "Ad "
                        + adId
                        + " was not found in account "
                        + customerId
                        + ". Use google_list_ads to see ad ids."));
  }

  // An empty string is a real value here — it is how a display path is removed — so
  // presence, not non-nullness, decides whether the field travels.
  private String path(JsonNode args, String field) {
    if (!args.hasNonNull(field)) {
      return null;
    }
    String value = args.get(field).asText().trim();
    if (value.length() > GoogleAdsEditSupport.MAX_PATH_LENGTH) {
      throw new McpToolException(
          field + " must be at most " + GoogleAdsEditSupport.MAX_PATH_LENGTH + " characters.");
    }
    return value;
  }

  private void writeAssets(ArrayNode arr, List<GoogleAdTextAssetDto> assets) {
    for (GoogleAdTextAssetDto asset : assets) {
      ObjectNode node = arr.addObject();
      node.put("text", asset.text());
      node.put("pinned", asset.pinnedField());
    }
  }

  private String summary(List<GoogleAdTextAssetDto> assets) {
    return String.join(" | ", assets.stream().map(GoogleAdTextAssetDto::text).toList());
  }

  private String text(
      GoogleAdDto ad,
      String adId,
      List<GoogleAdTextAssetDto> headlines,
      List<GoogleAdTextAssetDto> descriptions,
      String finalUrl,
      List<GoogleAdTextAssetDto> effectiveHeadlines,
      List<GoogleAdTextAssetDto> effectiveDescriptions) {
    StringBuilder text = new StringBuilder("Ad " + adId + " rewritten:");
    if (headlines != null) {
      text.append(" ").append(headlines.size()).append(" headlines");
    }
    if (descriptions != null) {
      text.append(headlines != null ? " and " : " ")
          .append(descriptions.size())
          .append(" descriptions");
    }
    if (headlines != null || descriptions != null) {
      text.append(" replaced.");
    }
    if (finalUrl != null) {
      text.append(" Landing page set to ").append(finalUrl).append(".");
    }
    text.append(
        " The ad keeps its id and its performance history, and Google reviews it again after an"
            + " edit, so its approval status reads UNDER_REVIEW for a while (it was "
            + ad.approvalStatus()
            + ").");
    String droppedPins =
        droppedPins(ad.headlines(), effectiveHeadlines, ad.descriptions(), effectiveDescriptions);
    if (!droppedPins.isEmpty()) {
      text.append(
          " Note: the ad had pinned assets that the new copy does not repeat ("
              + droppedPins
              + "), so those pins are gone and Google rotates those slots freely again.");
    }
    return text.toString();
  }

  // A replaced list drops every pin it does not carry, which quietly changes how the ad
  // rotates — worth one sentence rather than a silent behaviour change.
  private String droppedPins(
      List<GoogleAdTextAssetDto> oldHeadlines,
      List<GoogleAdTextAssetDto> newHeadlines,
      List<GoogleAdTextAssetDto> oldDescriptions,
      List<GoogleAdTextAssetDto> newDescriptions) {
    List<String> dropped =
        java.util.stream.Stream.concat(
                pinsOf(oldHeadlines).stream().filter(pin -> !pinsOf(newHeadlines).contains(pin)),
                pinsOf(oldDescriptions).stream()
                    .filter(pin -> !pinsOf(newDescriptions).contains(pin)))
            .toList();
    return String.join(", ", dropped);
  }

  private List<String> pinsOf(List<GoogleAdTextAssetDto> assets) {
    return assets.stream()
        .map(GoogleAdTextAssetDto::pinnedField)
        .filter(pin -> pin != null)
        .toList();
  }
}
