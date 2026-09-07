package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdDto;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
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

// Adds a second (or third) responsive search ad to an ad group that already exists.
// google_update_ad could only rewrite the ad that was there, which is the wrong answer to
// "test this headline against the current one": an A/B test needs two ads, and overwriting
// the incumbent destroys the thing being compared. The only other way to get an ad into an
// existing ad group was to open Google Ads and retype it.
//
// The new ad is created PAUSED by default so its copy can be read back before it spends
// anything — google_set_status turns it on. Google reviews every new ad before it serves.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCreateAdTool implements AdsTool {

  private static final String RESPONSIVE_SEARCH_AD = "RESPONSIVE_SEARCH_AD";
  private static final String DEFAULT_STATUS = "PAUSED";

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_create_ad";
  }

  @Override
  public String description() {
    return "Create a new responsive search ad in an ad group that already exists, without touching"
        + " the ads already running there. This is how you test new copy against current copy:"
        + " the existing ad keeps serving and its history, and the new one competes alongside it."
        + " The ad is created PAUSED unless you pass status ENABLED, and Google reviews it before"
        + " it can serve. Google allows "
        + GoogleAdsEditSupport.MAX_ENABLED_RSAS_PER_AD_GROUP
        + " enabled responsive search ads per ad group. To change the copy of an existing ad"
        + " instead of adding one, use google_update_ad; to build a whole new campaign, use"
        + " google_create_campaign; to turn this ad on afterwards, use google_set_status.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "ad_group_id",
        "string",
        "Ad group the new ad goes into, as reported by google_list_campaigns with"
            + " include_ad_groups or by google_list_ads.");
    ObjectNode headlines =
        McpSchemas.objectArrayProp(
            schema,
            "headlines",
            GoogleAdsEditSupport.MIN_HEADLINES
                + "-"
                + GoogleAdsEditSupport.MAX_HEADLINES
                + " headlines of at most "
                + GoogleAdsEditSupport.MAX_HEADLINE_LENGTH
                + " characters. An entry may be a plain string or an object with text and pinned.");
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
            GoogleAdsEditSupport.MIN_DESCRIPTIONS
                + "-"
                + GoogleAdsEditSupport.MAX_DESCRIPTIONS
                + " descriptions of at most "
                + GoogleAdsEditSupport.MAX_DESCRIPTION_LENGTH
                + " characters.");
    McpSchemas.prop(descriptions, "text", "string", "Description text.");
    ObjectNode descriptionPin =
        McpSchemas.prop(descriptions, "pinned", "string", "Slot to pin this description to.");
    McpSchemas.enumValues(descriptionPin, GoogleAdsEditSupport.DESCRIPTION_PINS);
    McpSchemas.prop(
        schema, "final_url", "string", "Landing page the ad clicks through to, an http(s) URL.");
    McpSchemas.prop(
        schema,
        "path1",
        "string",
        "First display path segment shown after the domain, at most "
            + GoogleAdsEditSupport.MAX_PATH_LENGTH
            + " characters. Optional.");
    McpSchemas.prop(schema, "path2", "string", "Second display path segment. Requires path1.");
    ObjectNode status =
        McpSchemas.prop(
            schema,
            "status",
            "string",
            "Whether the ad starts on or off. Defaults to "
                + DEFAULT_STATUS
                + " so the copy can be reviewed before it spends anything.");
    McpSchemas.enumValues(status, GoogleAdsEditSupport.STATUSES);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "ad_group_id", "headlines", "descriptions", "final_url");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "adId", "string", "Id of the ad that was created.");
    McpSchemas.prop(schema, "adGroupId", "string", "Ad group it was created in.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that ad group belongs to.");
    McpSchemas.prop(
        schema,
        "status",
        "string",
        "Configured state of the new ad, ENABLED or PAUSED. A PAUSED ad never serves until"
            + " google_set_status enables it.");
    ObjectNode headline =
        McpSchemas.objectArrayProp(schema, "headlines", "Headlines the ad was created with.");
    McpSchemas.nullableProp(headline, "text", "string", "Headline text.");
    McpSchemas.nullableProp(headline, "pinned", "string", "Slot it is pinned to, if any.");
    ObjectNode description =
        McpSchemas.objectArrayProp(schema, "descriptions", "Descriptions the ad was created with.");
    McpSchemas.nullableProp(description, "text", "string", "Description text.");
    McpSchemas.nullableProp(description, "pinned", "string", "Slot it is pinned to, if any.");
    McpSchemas.stringArrayProp(schema, "finalUrls", "Landing pages the ad clicks through to.");
    McpSchemas.nullableProp(schema, "path1", "string", "First display path, if one was set.");
    McpSchemas.nullableProp(schema, "path2", "string", "Second display path, if one was set.");
    McpSchemas.prop(
        schema,
        "otherAdsInGroup",
        "integer",
        "How many responsive search ads were already in the ad group before this one.");
    McpSchemas.required(
        schema,
        "adId",
        "adGroupId",
        "campaignId",
        "status",
        "headlines",
        "descriptions",
        "finalUrls",
        "otherAdsInGroup");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Not destructive: it adds an ad and leaves every existing one exactly as it was. Not
    // idempotent: calling it twice with the same arguments produces two ads, not one.
    return ToolAnnotations.write(false, false);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("ad_group_id")) {
      throw new McpToolException("ad_group_id is required.");
    }
    if (!args.hasNonNull("final_url")) {
      throw new McpToolException("final_url is required — an ad needs a landing page.");
    }
    String adGroupId = args.get("ad_group_id").asText();

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleAdGroupDto adGroup =
        support.requireAdGroup(connection, token, customerId, loginCustomerId, adGroupId);

    List<GoogleAdTextAssetDto> headlines =
        support.parseAdAssets(
            args.get("headlines"),
            "headlines",
            GoogleAdsEditSupport.MIN_HEADLINES,
            GoogleAdsEditSupport.MAX_HEADLINES,
            GoogleAdsEditSupport.MAX_HEADLINE_LENGTH,
            GoogleAdsEditSupport.HEADLINE_PINS);
    List<GoogleAdTextAssetDto> descriptions =
        support.parseAdAssets(
            args.get("descriptions"),
            "descriptions",
            GoogleAdsEditSupport.MIN_DESCRIPTIONS,
            GoogleAdsEditSupport.MAX_DESCRIPTIONS,
            GoogleAdsEditSupport.MAX_DESCRIPTION_LENGTH,
            GoogleAdsEditSupport.DESCRIPTION_PINS);
    String finalUrl = support.requireHttpUrl(args.get("final_url").asText().trim(), "final_url");
    String path1 = path(args, "path1");
    String path2 = path(args, "path2");
    if (path2 != null && !path2.isBlank() && (path1 == null || path1.isBlank())) {
      throw new McpToolException("path2 needs path1 — set path1 as well, or drop path2.");
    }
    String status = status(args);

    List<GoogleAdDto> existing =
        existingSearchAds(connection, token, customerId, loginCustomerId, adGroupId);
    requireRoomForAnotherAd(existing, adGroupId, status);

    String argsSummary =
        "adGroup="
            + adGroupId
            + ";headlines="
            + headlines.size()
            + ";descriptions="
            + descriptions.size()
            + ";finalUrl="
            + finalUrl
            + ";path1="
            + path1
            + ";path2="
            + path2
            + ";status="
            + status;
    log.debug("google_create_ad user={} {}", userId, argsSummary);
    try {
      String resourceName =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createResponsiveSearchAd(
                          token,
                          customerId,
                          loginCustomerId,
                          "customers/" + customerId + "/adGroups/" + adGroupId,
                          finalUrl,
                          headlines,
                          descriptions,
                          path1,
                          path2,
                          status));
      String adId = adIdOf(resourceName);
      audit.record(userId, name(), WriteKind.CREATE, argsSummary, adId, true, null);
      audit.alert(
          "<b>MCP Ads: google_create_ad</b>\nUser: "
              + userId
              + "\nAd: "
              + adId
              + " ("
              + status
              + ") in ad group "
              + adGroupId
              + " of campaign "
              + adGroup.campaignId()
              + "\nHeadlines: "
              + summary(headlines)
              + "\nDescriptions: "
              + summary(descriptions)
              + "\nFinal URL: "
              + finalUrl);

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("adId", adId);
      structured.put("adGroupId", adGroupId);
      structured.put("campaignId", adGroup.campaignId());
      structured.put("status", status);
      writeAssets(structured.putArray("headlines"), headlines);
      writeAssets(structured.putArray("descriptions"), descriptions);
      structured.putArray("finalUrls").add(finalUrl);
      structured.put("path1", path1);
      structured.put("path2", path2);
      structured.put("otherAdsInGroup", existing.size());

      return ToolResult.ok(
          text(adId, adGroupId, adGroup, headlines, descriptions, finalUrl, status, existing),
          structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.CREATE, argsSummary, adGroupId, false, e.getMessage());
      throw e;
    }
  }

  private List<GoogleAdDto> existingSearchAds(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String adGroupId) {
    return adsService
        .call(
            connection,
            () ->
                adsService
                    .client()
                    .listAds(token, customerId, loginCustomerId, null, adGroupId, null))
        .stream()
        .filter(ad -> RESPONSIVE_SEARCH_AD.equals(ad.type()))
        .toList();
  }

  // Google refuses the fourth enabled responsive search ad in an ad group with an error
  // that names no limit, so the refusal happens here, naming the ads that are in the way.
  // A PAUSED ad does not count towards the limit, which is why the check is conditional.
  private void requireRoomForAnotherAd(
      List<GoogleAdDto> existing, String adGroupId, String status) {
    if (!"ENABLED".equals(status)) {
      return;
    }
    List<GoogleAdDto> enabled =
        existing.stream().filter(ad -> "ENABLED".equals(ad.status())).toList();
    if (enabled.size() < GoogleAdsEditSupport.MAX_ENABLED_RSAS_PER_AD_GROUP) {
      return;
    }
    throw new McpToolException(
        "Ad group "
            + adGroupId
            + " already has "
            + enabled.size()
            + " enabled responsive search ads ("
            + String.join(", ", enabled.stream().map(GoogleAdDto::id).toList())
            + ") and Google allows "
            + GoogleAdsEditSupport.MAX_ENABLED_RSAS_PER_AD_GROUP
            + ". Pause the weakest one with google_set_status and try again, rewrite one of them"
            + " with google_update_ad, or create this ad PAUSED by leaving status out.");
  }

  private String status(JsonNode args) {
    if (!args.hasNonNull("status")) {
      return DEFAULT_STATUS;
    }
    String status = args.get("status").asText().trim().toUpperCase();
    if (!GoogleAdsEditSupport.STATUSES.contains(status)) {
      throw new McpToolException(
          "status must be one of: " + String.join(", ", GoogleAdsEditSupport.STATUSES));
    }
    return status;
  }

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

  // adGroupAds resource ids are the composite {adGroupId}~{adId}.
  private String adIdOf(String resourceName) {
    if (resourceName == null) {
      throw new McpToolException(
          "Google accepted the ad but returned no resource name for it. Check the ad group with"
              + " google_list_ads before creating it again.");
    }
    String id = resourceName.substring(resourceName.lastIndexOf('/') + 1);
    int tilde = id.indexOf('~');
    return tilde >= 0 ? id.substring(tilde + 1) : id;
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
      String adId,
      String adGroupId,
      GoogleAdGroupDto adGroup,
      List<GoogleAdTextAssetDto> headlines,
      List<GoogleAdTextAssetDto> descriptions,
      String finalUrl,
      String status,
      List<GoogleAdDto> existing) {
    StringBuilder text =
        new StringBuilder(
            "Ad "
                + adId
                + " created "
                + status
                + " in ad group "
                + adGroupId
                + " (\""
                + adGroup.name()
                + "\") with "
                + headlines.size()
                + " headlines and "
                + descriptions.size()
                + " descriptions pointing at "
                + finalUrl
                + ".");
    if (DEFAULT_STATUS.equals(status)) {
      text.append(
          " It is paused, so it is not serving and not spending — enable it with google_set_status"
              + " once the copy reads right.");
    }
    text.append(" Google reviews every new ad before it can serve.");
    if (existing.isEmpty()) {
      text.append(" It is the only responsive search ad in the ad group.");
    } else {
      text.append(
          " The ad group's "
              + existing.size()
              + " existing responsive search ad"
              + (existing.size() == 1 ? "" : "s")
              + " were left exactly as they were, so the new copy competes against them rather"
              + " than replacing them. Compare them with google_get_insights once the new ad has"
              + " served for a couple of weeks.");
    }
    return text.toString();
  }
}
