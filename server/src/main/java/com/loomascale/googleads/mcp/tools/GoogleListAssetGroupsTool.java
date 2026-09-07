package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAssetGroupAssetDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupQuery;
import com.loomascale.googleads.client.dto.GoogleAssetGroupSignalDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.money.Money;
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
import org.springframework.stereotype.Component;

// Performance Max asset groups: where a PMax campaign actually spends. The campaign
// row hides which creative set carries the budget, so auditing PMax means reading
// this level. Assets and signals are opt-in because each is another query, and a
// full audit should still cost one request. Money is in minor units.
@Component
@RequiredArgsConstructor
public class GoogleListAssetGroupsTool implements AdsTool {

  // GAQL DURING keywords the tool accepts as date_preset.
  private static final List<String> DATE_PRESETS =
      List.of(
          "TODAY",
          "YESTERDAY",
          "LAST_7_DAYS",
          "LAST_14_DAYS",
          "LAST_30_DAYS",
          "THIS_MONTH",
          "LAST_MONTH");
  // Ad strengths that mean the group does not have enough to compete.
  private static final List<String> WEAK_AD_STRENGTHS = List.of("POOR", "AVERAGE", "PENDING");

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_asset_groups";
  }

  @Override
  public String description() {
    return "Audit Performance Max campaigns by asset group — the level PMax actually spends at."
        + " Returns each asset group's status, ad strength, why it is or is not serving, and its"
        + " impressions, clicks, cost, conversions and conversion value for the range, ordered by"
        + " spend. Set include_assets to also list the assets in each group with the slot they"
        + " fill and whether they are serving, and include_search_themes to list the search themes"
        + " and audience signals the campaign has been told to chase. For campaign-level numbers"
        + " use google_get_insights, and for the list of campaigns and their channel types use"
        + " google_list_campaigns.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(
        schema,
        "campaign_id",
        "string",
        "Only asset groups in this campaign. Get PMax campaign ids from google_list_campaigns.");
    McpSchemas.prop(schema, "asset_group_id", "string", "Only this asset group.");
    ObjectNode preset =
        McpSchemas.prop(
            schema,
            "date_preset",
            "string",
            "Preset date range. Default LAST_30_DAYS. Ignored when since/until are set.");
    McpSchemas.enumValues(preset, DATE_PRESETS);
    McpSchemas.prop(schema, "since", "string", "Custom range start, YYYY-MM-DD.");
    McpSchemas.prop(schema, "until", "string", "Custom range end, YYYY-MM-DD.");
    McpSchemas.prop(
        schema, "limit", "integer", "Max rows, highest spend first. Default 100, max 1000.");
    McpSchemas.prop(
        schema,
        "include_assets",
        "boolean",
        "Also list the assets in each group with their field type and whether they are"
            + " serving. Default false.");
    McpSchemas.prop(
        schema,
        "include_search_themes",
        "boolean",
        "Also list the search theme and audience signals feeding each group. Default false.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the asset groups belong to.");
    McpSchemas.prop(schema, "dateRange", "string", "Range the metrics cover.");
    ObjectNode group =
        McpSchemas.objectArrayProp(schema, "assetGroups", "One row per asset group.");
    McpSchemas.nullableProp(group, "id", "string", "Asset group id.");
    McpSchemas.nullableProp(group, "name", "string", "Asset group name.");
    McpSchemas.nullableProp(group, "status", "string", "ENABLED, PAUSED or REMOVED.");
    McpSchemas.nullableProp(
        group, "primaryStatus", "string", "Whether the group is eligible to serve.");
    McpSchemas.stringArrayProp(
        group, "primaryStatusReasons", "Why the group is not serving, when it is not.");
    McpSchemas.nullableProp(
        group,
        "adStrength",
        "string",
        "Google's read on whether the group has enough assets to compete: POOR to EXCELLENT.");
    McpSchemas.nullableProp(group, "campaignId", "string", "Campaign the group belongs to.");
    McpSchemas.nullableProp(group, "campaignName", "string", "Campaign name.");
    McpSchemas.prop(group, "impressions", "integer", "Impressions.");
    McpSchemas.prop(group, "clicks", "integer", "Clicks.");
    McpSchemas.moneyProp(group, "cost", "Cost over the range.");
    McpSchemas.prop(group, "ctr", "number", "Click-through rate (0-1).");
    McpSchemas.prop(group, "conversions", "number", "Conversions over the range.");
    McpSchemas.moneyProp(group, "conversionsValue", "Conversion value over the range.");
    ObjectNode asset =
        McpSchemas.objectArrayProp(
            schema, "assets", "Assets in those groups. Present only when include_assets was set.");
    McpSchemas.nullableProp(asset, "assetGroupId", "string", "Group the asset is linked to.");
    McpSchemas.nullableProp(asset, "assetId", "string", "Asset id.");
    McpSchemas.nullableProp(
        asset,
        "fieldType",
        "string",
        "Slot the asset fills, e.g. HEADLINE, DESCRIPTION, MARKETING_IMAGE, YOUTUBE_VIDEO.");
    McpSchemas.nullableProp(asset, "status", "string", "Link status.");
    McpSchemas.nullableProp(
        asset, "primaryStatus", "string", "Whether the asset is eligible to serve.");
    McpSchemas.stringArrayProp(
        asset, "primaryStatusReasons", "Why the asset is not serving, when it is not.");
    McpSchemas.nullableProp(
        asset, "text", "string", "Asset text. Null for image and video assets.");
    ObjectNode signal =
        McpSchemas.objectArrayProp(
            schema,
            "signals",
            "Search theme and audience signals. Present only when include_search_themes was set.");
    McpSchemas.nullableProp(signal, "assetGroupId", "string", "Group the signal belongs to.");
    McpSchemas.nullableProp(signal, "kind", "string", "SEARCH_THEME or AUDIENCE.");
    McpSchemas.nullableProp(
        signal,
        "value",
        "string",
        "The theme text, or the audience resource name for an audience signal.");
    McpSchemas.nullableProp(
        signal,
        "resourceName",
        "string",
        "Handle for this signal. Pass the ones you want to keep to google_update_search_themes;"
            + " Google has no way to edit a signal in place, so changing a set means removing"
            + " the signals that dropped out and creating the new ones.");
    McpSchemas.prop(
        schema,
        "truncated",
        "boolean",
        "True when the row cap was reached, so more asset groups exist than were returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "dateRange", "assetGroups", "truncated");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    return ToolAnnotations.readOnly();
  }

  @Override
  public boolean requiresWriteScope() {
    return false;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    // Arguments first: decrypting a token can refresh it against Google, which is
    // wasted work on a call that cannot run.
    String since = args.hasNonNull("since") ? args.get("since").asText() : null;
    String until = args.hasNonNull("until") ? args.get("until").asText() : null;
    String preset = null;
    if (since == null || until == null) {
      preset = args.hasNonNull("date_preset") ? args.get("date_preset").asText() : "LAST_30_DAYS";
      if (!DATE_PRESETS.contains(preset)) {
        throw new McpToolException(
            "date_preset must be one of: " + String.join(", ", DATE_PRESETS));
      }
      since = null;
      until = null;
    }
    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;
    String assetGroupId =
        args.hasNonNull("asset_group_id") ? args.get("asset_group_id").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : 0;
    boolean includeAssets =
        args.hasNonNull("include_assets") && args.get("include_assets").asBoolean(false);
    boolean includeSearchThemes =
        args.hasNonNull("include_search_themes")
            && args.get("include_search_themes").asBoolean(false);

    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleAssetGroupQuery query =
        new GoogleAssetGroupQuery(
            campaignId,
            assetGroupId,
            preset,
            since,
            until,
            limit,
            includeAssets,
            includeSearchThemes);
    List<GoogleAssetGroupDto> groups =
        adsService.call(
            connection,
            () -> adsService.client().listAssetGroups(token, customerId, loginCustomerId, query));
    boolean truncated = groups.size() >= GoogleAdsApiClient.clampLimit(limit);

    String range = preset != null ? preset : since + ".." + until;
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("dateRange", range);
    structured.put("truncated", truncated);
    ArrayNode arr = structured.putArray("assetGroups");
    StringBuilder text =
        new StringBuilder("Performance Max asset groups for " + customerId + " (" + range + "):\n");

    for (GoogleAssetGroupDto group : groups) {
      ObjectNode node = arr.addObject();
      node.put("id", group.id());
      node.put("name", group.name());
      node.put("status", group.status());
      node.put("primaryStatus", group.primaryStatus());
      ArrayNode reasons = node.putArray("primaryStatusReasons");
      group.primaryStatusReasons().forEach(reasons::add);
      node.put("adStrength", group.adStrength());
      node.put("campaignId", group.campaignId());
      node.put("campaignName", group.campaignName());
      node.put("impressions", group.impressions());
      node.put("clicks", group.clicks());
      node.put("cost", Money.majorUnits(group.costCents()));
      node.put("ctr", group.ctr());
      node.put("conversions", group.conversions());
      node.put("conversionsValue", Money.majorUnits(group.conversionsValueCents()));

      text.append("• ")
          .append(group.name())
          .append(" (")
          .append(group.campaignName())
          .append("): ")
          .append(Money.display(group.costCents(), currency))
          .append(" spent, ")
          .append(group.conversions())
          .append(" conversions, ad strength ")
          .append(group.adStrength())
          .append(WEAK_AD_STRENGTHS.contains(group.adStrength()) ? " [needs more assets]" : "")
          .append(
              group.primaryStatusReasons().isEmpty()
                  ? ""
                  : " [not serving: " + String.join(", ", group.primaryStatusReasons()) + "]")
          .append("\n");
    }
    if (groups.isEmpty()) {
      text.append(
          "(no asset groups — either this account runs no Performance Max campaigns, or the"
              + " campaign_id given is not a PMax campaign)");
    }

    if (includeAssets) {
      List<GoogleAssetGroupAssetDto> assets =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .listAssetGroupAssets(token, customerId, loginCustomerId, query));
      ArrayNode assetArr = structured.putArray("assets");
      for (GoogleAssetGroupAssetDto asset : assets) {
        ObjectNode node = assetArr.addObject();
        node.put("assetGroupId", asset.assetGroupId());
        node.put("assetId", asset.assetId());
        node.put("fieldType", asset.fieldType());
        node.put("status", asset.status());
        node.put("primaryStatus", asset.primaryStatus());
        ArrayNode assetReasons = node.putArray("primaryStatusReasons");
        asset.primaryStatusReasons().forEach(assetReasons::add);
        node.put("text", asset.text());
      }
      text.append("Assets: ").append(assets.size()).append(" linked\n");
      for (GoogleAssetGroupAssetDto asset : assets) {
        text.append("  - ")
            .append(asset.fieldType())
            .append(asset.text() == null ? "" : ": \"" + asset.text() + "\"")
            .append(asset.primaryStatus() == null ? "" : " (" + asset.primaryStatus() + ")")
            .append("\n");
      }
    }

    if (includeSearchThemes) {
      List<GoogleAssetGroupSignalDto> signals =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .listAssetGroupSignals(token, customerId, loginCustomerId, query));
      ArrayNode signalArr = structured.putArray("signals");
      for (GoogleAssetGroupSignalDto signal : signals) {
        ObjectNode node = signalArr.addObject();
        node.put("assetGroupId", signal.assetGroupId());
        node.put("kind", signal.kind());
        node.put("value", signal.value());
        node.put("resourceName", signal.resourceName());
      }
      text.append("Signals: ").append(signals.size()).append("\n");
      for (GoogleAssetGroupSignalDto signal : signals) {
        text.append("  - ").append(signal.kind()).append(": ").append(signal.value()).append("\n");
      }
    }

    if (truncated) {
      text.append("(capped at ")
          .append(groups.size())
          .append(" asset groups by spend — more exist; narrow by campaign_id)");
    }
    return ToolResult.ok(text.toString(), structured);
  }
}
