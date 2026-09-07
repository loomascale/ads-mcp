package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAssetLinkSpec;
import com.loomascale.googleads.client.dto.GoogleCampaignAssetDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleMediaSlot;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.googleads.mcp.service.GoogleAssetMediaService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// The business name and logos of a campaign — the assets Google calls brand assets.
//
// This tool exists because of one flag. Google enables brand guidelines by default on
// Performance Max campaigns created since v21, and when they are on it holds BUSINESS_NAME,
// LOGO and LANDSCAPE_LOGO on the CAMPAIGN rather than on each asset group. So the same three
// field types live in two different places depending on that flag, and
// google_update_asset_group_assets and google_update_asset_group_media both refuse them and
// point here. Without this tool those three fields would be the one part of a modern
// Performance Max campaign that stayed uneditable.
//
// Brand guidelines also make these assets mandatory, which is why the swap is one atomic
// mutate: the campaign must never be momentarily without the logo Google requires it to have.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateBrandAssetsTool implements AdsTool {

  private static final String BUSINESS_NAME = "BUSINESS_NAME";
  private static final String LOGO = "LOGO";
  private static final String LANDSCAPE_LOGO = "LANDSCAPE_LOGO";
  private static final int MAX_BRAND_EDITS_PER_DAY = 20;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final GoogleAssetMediaService media;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_brand_assets";
  }

  @Override
  public String description() {
    return "Change the business name and logos of a Google Ads campaign. On a Performance Max"
        + " campaign with brand guidelines enabled — Google's default for campaigns created"
        + " recently — these three assets live on the campaign rather than on each asset group,"
        + " which is why google_update_asset_group_assets and google_update_asset_group_media"
        + " refuse them and send you here. Logos are given as public https URLs, which this"
        + " server downloads and uploads to Google: square logos must be 1:1 and at least 128x128,"
        + " landscape logos 4:1 and at least 512x128. Each list REPLACES the whole slot and a"
        + " slot you omit is left untouched. Brand guidelines make a business name and at least"
        + " one logo mandatory, so neither can be emptied.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign whose brand assets to change.");
    McpSchemas.prop(
        schema,
        "business_name",
        "string",
        "The advertiser name Google shows alongside the ad, at most "
            + GoogleAdsEditSupport.PMAX_MAX_BUSINESS_NAME_LENGTH
            + " characters.");
    McpSchemas.stringArrayProp(
        schema,
        "logos",
        "Complete new set of public https URLs for square logos: 1:1 and at least 128x128"
            + " pixels. Replaces the whole slot; omit to leave the logos alone.");
    McpSchemas.stringArrayProp(
        schema,
        "landscape_logos",
        "Complete new set of public https URLs for landscape logos: 4:1 and at least 512x128"
            + " pixels. Optional slot.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was changed.");
    McpSchemas.prop(
        schema,
        "changed",
        "boolean",
        "False when every asset you passed was already linked, in which case nothing was sent to"
            + " Google.");
    ObjectNode field =
        McpSchemas.objectArrayProp(schema, "fields", "One entry per slot this call touched.");
    McpSchemas.prop(field, "fieldType", "string", "BUSINESS_NAME, LOGO or LANDSCAPE_LOGO.");
    McpSchemas.stringArrayProp(field, "added", "Assets newly linked to the campaign.");
    McpSchemas.stringArrayProp(field, "removed", "Assets unlinked from the campaign.");
    McpSchemas.stringArrayProp(field, "kept", "Assets that were already linked.");
    McpSchemas.prop(field, "countAfter", "integer", "How many assets fill the slot now.");
    McpSchemas.nullableProp(schema, "campaignName", "string", "Name of the campaign.");
    McpSchemas.prop(
        schema,
        "brandGuidelinesEnabled",
        "boolean",
        "Whether Google holds these assets on the campaign for this campaign. False means the"
            + " campaign's asset groups own them instead, and this call was still applied at the"
            + " campaign level.");
    McpSchemas.required(schema, "campaignId", "changed", "fields", "brandGuidelinesEnabled");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: a logo or business name this call does not repeat is unlinked from the
    // campaign and Google cannot restore the link, and brand guidelines make these assets
    // mandatory, so the campaign's own eligibility depends on them. Idempotent: the values are
    // absolute, Google deduplicates an asset by content, and an unchanged call relinks
    // nothing.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("campaign_id")) {
      throw new McpToolException("campaign_id is required.");
    }
    String campaignId = args.get("campaign_id").asText();
    if (audit.countToday(userId, name()) >= MAX_BRAND_EDITS_PER_DAY) {
      throw new McpToolException(
          "You have already made "
              + MAX_BRAND_EDITS_PER_DAY
              + " brand asset edits today, which is the daily limit — each one"
              + " downloads files and permanently adds assets to your Google Ads account.");
    }

    String businessName =
        args.hasNonNull("business_name") ? args.get("business_name").asText().trim() : null;
    if (businessName != null) {
      if (businessName.isBlank()) {
        throw new McpToolException(
            "business_name cannot be blank. Brand guidelines make it mandatory, so it cannot be"
                + " removed either.");
      }
      if (businessName.length() > GoogleAdsEditSupport.PMAX_MAX_BUSINESS_NAME_LENGTH) {
        throw new McpToolException(
            "business_name must be at most "
                + GoogleAdsEditSupport.PMAX_MAX_BUSINESS_NAME_LENGTH
                + " characters (got "
                + businessName.length()
                + ").");
      }
    }
    Map<GoogleMediaSlot, List<String>> logoUrls = new LinkedHashMap<>();
    int totalUrls = 0;
    for (String fieldType : List.of(LOGO, LANDSCAPE_LOGO)) {
      GoogleMediaSlot slot = GoogleAssetMediaService.slotFor(fieldType).orElseThrow();
      if (!args.has(slot.argument())) {
        continue;
      }
      List<String> urls = parseUrls(args.get(slot.argument()), slot);
      totalUrls += urls.size();
      logoUrls.put(slot, urls);
    }
    if (totalUrls > GoogleAssetMediaService.MAX_URLS_PER_CALL) {
      throw new McpToolException(
          "At most "
              + GoogleAssetMediaService.MAX_URLS_PER_CALL
              + " logo URLs per call (got "
              + totalUrls
              + "), because each one is downloaded and uploaded.");
    }
    if (businessName == null && logoUrls.isEmpty()) {
      throw new McpToolException(
          "Provide business_name, logos, landscape_logos, or a combination — there is nothing to"
              + " change.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleCampaignDto campaign =
        support.requireCampaign(connection, token, customerId, loginCustomerId, campaignId);

    List<GoogleCampaignAssetDto> current =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listCampaignAssets(token, customerId, loginCustomerId, campaignId));

    Map<String, List<String>> added = new LinkedHashMap<>();
    Map<String, List<String>> removed = new LinkedHashMap<>();
    Map<String, List<String>> kept = new LinkedHashMap<>();
    Map<String, Integer> countAfter = new LinkedHashMap<>();
    List<GoogleAssetLinkSpec> toLink = new ArrayList<>();
    List<GoogleAssetLinkSpec> toUnlink = new ArrayList<>();

    if (businessName != null) {
      List<GoogleCampaignAssetDto> existing = assetsOf(current, BUSINESS_NAME);
      List<String> addedValues = new ArrayList<>();
      List<String> removedValues = new ArrayList<>();
      List<String> keptValues = new ArrayList<>();
      GoogleCampaignAssetDto match =
          existing.stream()
              .filter(asset -> businessName.equals(asset.text()))
              .findFirst()
              .orElse(null);
      if (match != null) {
        keptValues.add(businessName);
      } else {
        existing.forEach(
            asset -> {
              removedValues.add(asset.text());
              toUnlink.add(
                  new GoogleAssetLinkSpec(
                      asset.assetResourceName(), asset.assetId(), BUSINESS_NAME));
            });
        List<String> resourceNames =
            adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .createTextAssets(
                            token, customerId, loginCustomerId, List.of(businessName)));
        toLink.add(new GoogleAssetLinkSpec(resourceNames.get(0), null, BUSINESS_NAME));
        addedValues.add(businessName);
      }
      record(
          added, removed, kept, countAfter, BUSINESS_NAME, addedValues, removedValues, keptValues);
    }

    for (Map.Entry<GoogleMediaSlot, List<String>> entry : logoUrls.entrySet()) {
      GoogleMediaSlot slot = entry.getKey();
      List<GoogleCampaignAssetDto> existing = assetsOf(current, slot.fieldType());
      Set<String> requestedResourceNames = new LinkedHashSet<>();
      for (String url : entry.getValue()) {
        // Same content-dedupe trick as the asset-group media tool: creating the asset is how
        // an image already in the account is recognised.
        requestedResourceNames.add(
            media.uploadImage(connection, token, customerId, loginCustomerId, slot, url));
      }
      Set<String> requestedAssetIds = new LinkedHashSet<>();
      requestedResourceNames.forEach(name -> requestedAssetIds.add(lastSegment(name)));

      List<String> keptIds = new ArrayList<>();
      List<String> addedIds = new ArrayList<>();
      for (String resourceName : requestedResourceNames) {
        String assetId = lastSegment(resourceName);
        if (existing.stream().anyMatch(asset -> assetId.equals(asset.assetId()))) {
          keptIds.add(assetId);
        } else {
          addedIds.add(assetId);
          toLink.add(new GoogleAssetLinkSpec(resourceName, null, slot.fieldType()));
        }
      }
      List<String> removedIds = new ArrayList<>();
      for (GoogleCampaignAssetDto asset : existing) {
        if (!requestedAssetIds.contains(asset.assetId())) {
          removedIds.add(asset.assetId());
          toUnlink.add(
              new GoogleAssetLinkSpec(
                  asset.assetResourceName(), asset.assetId(), slot.fieldType()));
        }
      }
      record(added, removed, kept, countAfter, slot.fieldType(), addedIds, removedIds, keptIds);
    }

    String argsSummary =
        "campaign="
            + campaignId
            + ";link="
            + toLink.size()
            + ";unlink="
            + toUnlink.size()
            + ";fields="
            + String.join("/", countAfter.keySet());
    log.debug("google_update_brand_assets user={} {}", userId, argsSummary);

    if (toLink.isEmpty() && toUnlink.isEmpty()) {
      return ToolResult.ok(
          "Campaign \""
              + campaign.name()
              + "\" already has exactly those brand assets, so nothing was changed.",
          structured(campaignId, campaign, false, added, removed, kept, countAfter));
    }

    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .mutateCampaignAssetLinks(
                    token, customerId, loginCustomerId, campaignId, toLink, toUnlink);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_brand_assets</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaign.name()
              + " ("
              + campaignId
              + ")\nLinked: "
              + toLink.size()
              + "\nUnlinked: "
              + toUnlink.size());
      return ToolResult.ok(
          text(campaign, added, removed, kept, countAfter),
          structured(campaignId, campaign, true, added, removed, kept, countAfter));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, false, e.getMessage());
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
    if (LOGO.equals(slot.fieldType()) && urls.isEmpty()) {
      // Brand guidelines make a logo mandatory, so emptying the slot would leave the campaign
      // unable to serve.
      throw new McpToolException(
          "A campaign with brand guidelines needs at least one logo, so logos cannot be empty.");
    }
    if (urls.size() > slot.max()) {
      throw new McpToolException(
          slot.argument() + " takes at most " + slot.max() + " URLs (got " + urls.size() + ").");
    }
    return urls;
  }

  private List<GoogleCampaignAssetDto> assetsOf(
      List<GoogleCampaignAssetDto> assets, String fieldType) {
    return assets.stream().filter(asset -> fieldType.equals(asset.fieldType())).toList();
  }

  private void record(
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept,
      Map<String, Integer> countAfter,
      String fieldType,
      List<String> addedValues,
      List<String> removedValues,
      List<String> keptValues) {
    added.put(fieldType, addedValues);
    removed.put(fieldType, removedValues);
    kept.put(fieldType, keptValues);
    countAfter.put(fieldType, addedValues.size() + keptValues.size());
  }

  private String lastSegment(String resourceName) {
    return resourceName == null ? null : resourceName.substring(resourceName.lastIndexOf('/') + 1);
  }

  private ObjectNode structured(
      String campaignId,
      GoogleCampaignDto campaign,
      boolean changed,
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept,
      Map<String, Integer> countAfter) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("campaignId", campaignId);
    structured.put("changed", changed);
    ArrayNode fields = structured.putArray("fields");
    for (String fieldType : countAfter.keySet()) {
      ObjectNode node = fields.addObject();
      node.put("fieldType", fieldType);
      added.get(fieldType).forEach(node.putArray("added")::add);
      removed.get(fieldType).forEach(node.putArray("removed")::add);
      kept.get(fieldType).forEach(node.putArray("kept")::add);
      node.put("countAfter", countAfter.get(fieldType));
    }
    structured.put("campaignName", campaign.name());
    // Read from the campaign, not assumed: this tool works either way, and the reader needs to
    // know whether the asset groups own a copy of these assets as well.
    structured.put(
        "brandGuidelinesEnabled", Boolean.TRUE.equals(campaign.brandGuidelinesEnabled()));
    return structured;
  }

  private String text(
      GoogleCampaignDto campaign,
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept,
      Map<String, Integer> countAfter) {
    StringBuilder text = new StringBuilder();
    text.append("Updated the brand assets of campaign ").append(campaign.name()).append("\n");
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
    if (Boolean.TRUE.equals(campaign.brandGuidelinesEnabled())) {
      text.append(
          "This campaign has brand guidelines enabled, so these assets apply to every asset"
              + " group in it.");
    } else {
      // Worth saying: without the flag the asset groups carry their own copies, and those are
      // what actually serve.
      text.append(
          "This campaign does NOT have brand guidelines enabled, so its asset groups carry"
              + " their own business name and logos and those are the ones that serve. Use"
              + " google_update_asset_group_assets and google_update_asset_group_media to change"
              + " those.");
    }
    return text.toString();
  }
}
