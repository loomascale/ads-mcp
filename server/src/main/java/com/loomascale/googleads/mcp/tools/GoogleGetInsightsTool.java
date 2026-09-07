package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleInsightsRowDto;
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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Performance metrics at account, campaign, ad group, or keyword level — the Google
// twin of meta_get_insights. Money is in minor units of the account currency.
@Component
@RequiredArgsConstructor
public class GoogleGetInsightsTool implements AdsTool {

  private static final List<String> LEVELS = List.of("account", "campaign", "ad_group", "keyword");
  // Impression share is a search-auction metric reported per campaign or ad group,
  // not per keyword criterion, so asking for it there fails at the API.
  private static final List<String> IMPRESSION_SHARE_LEVELS =
      List.of("account", "campaign", "ad_group");
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

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_get_insights";
  }

  @Override
  public String description() {
    return "Get Google Ads performance metrics — impressions, clicks, cost, CTR, average CPC,"
        + " conversions, and conversion value — for the whole account, per campaign, per ad group,"
        + " or per keyword, over a preset or custom date range. Split rows by device, day of week,"
        + " hour, or network with `segments`, and add search impression share and the share lost to"
        + " budget or rank with `include_impression_share` — that is how to tell whether a campaign"
        + " has room to spend more. The ad_group and keyword levels, and any segmented query, are"
        + " capped at the highest-impression rows. For the keyword list and why keywords do or do"
        + " not serve, use google_list_keywords; for the queries users actually typed, use"
        + " google_list_search_terms; for performance by location use google_get_geo_insights and by"
        + " audience google_get_audience_insights. If you also run Meta ads, that server"
        + " exposes meta_get_insights.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    ObjectNode level =
        McpSchemas.prop(schema, "level", "string", "Aggregation level. Default campaign.");
    McpSchemas.enumValues(level, LEVELS);
    McpSchemas.prop(
        schema, "ad_group_id", "string", "Only this ad group (ad_group and keyword levels only).");
    McpSchemas.prop(
        schema,
        "limit",
        "integer",
        "Max rows at ad_group and keyword level, highest impressions first. Default 100, max 1000.");
    ObjectNode preset =
        McpSchemas.prop(
            schema,
            "date_preset",
            "string",
            "Preset date range. Default LAST_7_DAYS. Ignored when since/until are set.");
    McpSchemas.enumValues(preset, DATE_PRESETS);
    McpSchemas.prop(schema, "since", "string", "Custom range start, YYYY-MM-DD.");
    McpSchemas.prop(schema, "until", "string", "Custom range end, YYYY-MM-DD.");
    McpSchemas.prop(schema, "campaign_id", "string", "Only this campaign (campaign level only).");
    McpSchemas.arrayProp(
        schema,
        "segments",
        "Split every row by these dimensions, e.g. [\"device\"] to compare mobile with desktop."
            + " Rows are multiplied by each dimension's values, so a segmented result is always"
            + " capped. Cannot be combined with include_impression_share.",
        GoogleAdsApiClient.SEGMENTS);
    McpSchemas.prop(
        schema,
        "include_impression_share",
        "boolean",
        "Also report search impression share and the share lost to budget and to rank."
            + " Levels account, campaign and ad_group only; at ad_group level Google does not"
            + " report the budget-lost share, so only impression share and rank-lost share are"
            + " returned. Default false.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the metrics belong to.");
    ObjectNode level = McpSchemas.prop(schema, "level", "string", "Aggregation level used.");
    McpSchemas.enumValues(level, LEVELS);
    ObjectNode row = McpSchemas.objectArrayProp(schema, "rows", "One row per entity.");
    McpSchemas.nullableProp(
        row,
        "entityId",
        "string",
        "Id of the entity the row aggregates — campaign, ad group, or keyword criterion."
            + " Null at account level.");
    McpSchemas.nullableProp(
        row,
        "entityName",
        "string",
        "Name of that entity, or the keyword text at keyword level. Null at account level.");
    McpSchemas.nullableProp(row, "campaignId", "string", "Campaign id, null at account level.");
    McpSchemas.nullableProp(row, "campaignName", "string", "Campaign name, null at account level.");
    McpSchemas.nullableProp(row, "adGroupId", "string", "Ad group id, null above ad group level.");
    McpSchemas.nullableProp(
        row, "adGroupName", "string", "Ad group name, null above ad group level.");
    McpSchemas.nullableProp(
        row, "matchType", "string", "Keyword match type, set at keyword level only.");
    McpSchemas.prop(row, "impressions", "integer", "Impressions.");
    McpSchemas.prop(row, "clicks", "integer", "Clicks.");
    McpSchemas.moneyProp(row, "cost", "Cost over the range.");
    McpSchemas.prop(row, "ctr", "number", "Click-through rate (0-1).");
    McpSchemas.moneyProp(row, "avgCpc", "Average cost per click.");
    McpSchemas.prop(row, "conversions", "number", "Conversions per the account's tracking setup.");
    McpSchemas.moneyProp(row, "conversionsValue", "Total conversion value.");
    McpSchemas.mapProp(
        row,
        "segments",
        "string",
        "Requested breakdown values for this row, e.g. {\"device\": \"MOBILE\"}. Present only when"
            + " segments were requested, and then the row key is the entity plus these values.");
    McpSchemas.nullableProp(
        row,
        "searchImpressionShare",
        "number",
        "Share of available search impressions won, as a fraction of 1. Present only when"
            + " include_impression_share was set. Google reports >0.9 and <0.1 as clamped"
            + " estimates.");
    McpSchemas.nullableProp(
        row,
        "searchBudgetLostImpressionShare",
        "number",
        "Fraction of available impressions lost because the budget ran out — the headroom a budget"
            + " increase could buy. Present only when include_impression_share was set, and null at"
            + " ad_group level where Google does not report it.");
    McpSchemas.nullableProp(
        row,
        "searchRankLostImpressionShare",
        "number",
        "Fraction of available impressions lost to Ad Rank, which more budget cannot fix."
            + " Present only when include_impression_share was set.");
    McpSchemas.prop(
        schema,
        "truncated",
        "boolean",
        "True when the row cap was reached, so more rows exist than were returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "level", "rows", "truncated");
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
    // Arguments are checked before the connection work: decrypting a token can
    // refresh it against Google, which is wasted on a call that cannot run.
    String level = args.hasNonNull("level") ? args.get("level").asText() : "campaign";
    if (!LEVELS.contains(level)) {
      throw new McpToolException("level must be one of: " + String.join(", ", LEVELS));
    }
    String since = args.hasNonNull("since") ? args.get("since").asText() : null;
    String until = args.hasNonNull("until") ? args.get("until").asText() : null;
    String preset = null;
    if (since == null || until == null) {
      preset = args.hasNonNull("date_preset") ? args.get("date_preset").asText() : "LAST_7_DAYS";
      if (!DATE_PRESETS.contains(preset)) {
        throw new McpToolException(
            "date_preset must be one of: " + String.join(", ", DATE_PRESETS));
      }
      since = null;
      until = null;
    }
    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;
    String adGroupId = args.hasNonNull("ad_group_id") ? args.get("ad_group_id").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : 0;
    List<String> segments = segments(args);
    boolean impressionShare =
        args.hasNonNull("include_impression_share")
            && args.get("include_impression_share").asBoolean(false);
    if (impressionShare && !IMPRESSION_SHARE_LEVELS.contains(level)) {
      throw new McpToolException(
          "include_impression_share is available at level "
              + String.join(", ", IMPRESSION_SHARE_LEVELS)
              + " only — impression share is not reported per keyword.");
    }
    // Google reports impression share for the whole auction, not per segment value;
    // asking for both produces a query the API rejects, so refuse it here with an
    // answer the model can act on instead.
    if (impressionShare && !segments.isEmpty()) {
      throw new McpToolException(
          "segments and include_impression_share cannot be combined. Call once for the segment"
              + " split and once for impression share.");
    }

    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleInsightsQuery query =
        new GoogleInsightsQuery(
            level, preset, since, until, campaignId, adGroupId, limit, segments, impressionShare);
    List<GoogleInsightsRowDto> rows =
        adsService.call(
            connection,
            () -> adsService.client().getInsights(token, customerId, loginCustomerId, query));
    // A full page back from a capped level means Google had more to give.
    boolean truncated =
        GoogleAdsApiClient.isRowCapped(query)
            && rows.size() >= GoogleAdsApiClient.clampLimit(limit);

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("level", level);
    structured.put("truncated", truncated);
    ArrayNode arr = structured.putArray("rows");
    String range = preset != null ? preset : since + ".." + until;
    StringBuilder text =
        new StringBuilder(
            "Google Ads " + level + " metrics for " + customerId + " (" + range + "):\n");
    for (GoogleInsightsRowDto row : rows) {
      ObjectNode node = arr.addObject();
      node.put("entityId", row.entityId());
      node.put("entityName", row.entityName());
      node.put("campaignId", row.campaignId());
      node.put("campaignName", row.campaignName());
      node.put("adGroupId", row.adGroupId());
      node.put("adGroupName", row.adGroupName());
      node.put("matchType", row.matchType());
      node.put("impressions", row.impressions());
      node.put("clicks", row.clicks());
      node.put("cost", Money.majorUnits(row.costCents()));
      node.put("ctr", row.ctr());
      node.put("avgCpc", Money.majorUnits(row.avgCpcCents()));
      node.put("conversions", row.conversions());
      node.put("conversionsValue", Money.majorUnits(row.conversionsValueCents()));
      if (!row.segments().isEmpty()) {
        ObjectNode segmentNode = node.putObject("segments");
        row.segments().forEach(segmentNode::put);
      }
      putNullableDouble(node, "searchImpressionShare", row.searchImpressionShare());
      putNullableDouble(
          node, "searchBudgetLostImpressionShare", row.searchBudgetLostImpressionShare());
      putNullableDouble(node, "searchRankLostImpressionShare", row.searchRankLostImpressionShare());
      text.append("• ")
          .append(row.entityName() == null ? "Account" : row.entityName())
          .append(segmentSuffix(row))
          .append(": ")
          .append(row.impressions())
          .append(" impressions, ")
          .append(row.clicks())
          .append(" clicks, ")
          .append(Money.display(row.costCents(), currency))
          .append(" spent")
          .append(row.conversions() > 0 ? ", " + row.conversions() + " conversions" : "")
          .append(impressionShareSuffix(row))
          .append("\n");
    }
    if (rows.isEmpty()) {
      text.append("(no data for this range)");
    }
    if (truncated) {
      text.append("(capped at ")
          .append(rows.size())
          .append(" rows by impressions — more exist; narrow by campaign_id or ad_group_id)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  // Validated here rather than in the client so the model gets the allowed values
  // back as a tool error instead of a Google API rejection.
  private List<String> segments(JsonNode args) {
    if (!args.hasNonNull("segments")) {
      return List.of();
    }
    List<String> segments = new ArrayList<>();
    for (JsonNode node : args.get("segments")) {
      String segment = node.asText();
      if (!GoogleAdsApiClient.SEGMENTS.contains(segment)) {
        throw new McpToolException(
            "segments may only contain: " + String.join(", ", GoogleAdsApiClient.SEGMENTS));
      }
      if (!segments.contains(segment)) {
        segments.add(segment);
      }
    }
    return segments;
  }

  private void putNullableDouble(ObjectNode node, String field, Double value) {
    if (value == null) {
      return;
    }
    node.put(field, value);
  }

  private String segmentSuffix(GoogleInsightsRowDto row) {
    if (row.segments().isEmpty()) {
      return "";
    }
    return " / " + String.join(" / ", row.segments().values());
  }

  // Percentages read better than fractions in the text block, and the two "lost"
  // numbers are the whole point: budget-lost is buyable, rank-lost is not.
  private String impressionShareSuffix(GoogleInsightsRowDto row) {
    if (row.searchImpressionShare() == null) {
      return "";
    }
    return String.format(
        ", IS %.0f%% (lost-budget %.0f%%, lost-rank %.0f%%)",
        row.searchImpressionShare() * 100,
        row.searchBudgetLostImpressionShare() == null
            ? 0
            : row.searchBudgetLostImpressionShare() * 100,
        row.searchRankLostImpressionShare() == null
            ? 0
            : row.searchRankLostImpressionShare() * 100);
  }
}
