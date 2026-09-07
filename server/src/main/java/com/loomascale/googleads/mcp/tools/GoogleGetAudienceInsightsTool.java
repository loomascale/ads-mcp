package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAudienceInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleAudienceRowDto;
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

// Performance by audience criterion — remarketing lists, interest categories and
// audience segments attached to ad groups. Answers "which audience is actually
// worth the bid adjustment". Money is in minor units of the account currency.
@Component
@RequiredArgsConstructor
public class GoogleGetAudienceInsightsTool implements AdsTool {

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
    return "google_get_audience_insights";
  }

  @Override
  public String description() {
    return "Get Google Ads performance broken down by audience: impressions, clicks, cost, CTR,"
        + " conversions and conversion value per audience criterion attached to an ad group"
        + " (remarketing lists, interest categories, audience segments), ordered by spend. Note"
        + " that Google returns the audience as a resource name, which is not always readable."
        + " For breakdowns by device, day of week, hour or network use google_get_insights with its"
        + " segments argument; for locations use google_get_geo_insights.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(schema, "campaign_id", "string", "Only this campaign.");
    McpSchemas.prop(schema, "ad_group_id", "string", "Only this ad group.");
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
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the metrics belong to.");
    McpSchemas.prop(schema, "dateRange", "string", "Range the metrics cover.");
    ObjectNode row = McpSchemas.objectArrayProp(schema, "rows", "One row per audience criterion.");
    McpSchemas.nullableProp(row, "criterionId", "string", "Criterion id of the audience.");
    McpSchemas.nullableProp(
        row, "audienceType", "string", "Criterion type, e.g. USER_LIST, USER_INTEREST, AUDIENCE.");
    McpSchemas.nullableProp(
        row,
        "audienceName",
        "string",
        "Audience resource name. Google does not always return this as readable text.");
    McpSchemas.nullableProp(row, "adGroupId", "string", "Ad group the audience is attached to.");
    McpSchemas.nullableProp(row, "adGroupName", "string", "Ad group name.");
    McpSchemas.nullableProp(row, "campaignId", "string", "Campaign the row belongs to.");
    McpSchemas.nullableProp(row, "campaignName", "string", "Campaign name.");
    McpSchemas.prop(row, "impressions", "integer", "Impressions.");
    McpSchemas.prop(row, "clicks", "integer", "Clicks.");
    McpSchemas.moneyProp(row, "cost", "Cost over the range.");
    McpSchemas.prop(row, "ctr", "number", "Click-through rate (0-1).");
    McpSchemas.prop(row, "conversions", "number", "Conversions over the range.");
    McpSchemas.moneyProp(row, "conversionsValue", "Conversion value over the range.");
    McpSchemas.prop(
        schema,
        "truncated",
        "boolean",
        "True when the row cap was reached, so more audiences exist than were returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "dateRange", "rows", "truncated");
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
    String adGroupId = args.hasNonNull("ad_group_id") ? args.get("ad_group_id").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : 0;

    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleAudienceInsightsQuery query =
        new GoogleAudienceInsightsQuery(campaignId, adGroupId, preset, since, until, limit);
    List<GoogleAudienceRowDto> rows =
        adsService.call(
            connection,
            () ->
                adsService.client().getAudienceInsights(token, customerId, loginCustomerId, query));
    boolean truncated = rows.size() >= GoogleAdsApiClient.clampLimit(limit);

    String range = preset != null ? preset : since + ".." + until;
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("dateRange", range);
    structured.put("truncated", truncated);
    ArrayNode arr = structured.putArray("rows");
    StringBuilder text =
        new StringBuilder(
            "Google Ads performance by audience for " + customerId + " (" + range + "):\n");

    for (GoogleAudienceRowDto row : rows) {
      ObjectNode node = arr.addObject();
      node.put("criterionId", row.criterionId());
      node.put("audienceType", row.audienceType());
      node.put("audienceName", row.audienceName());
      node.put("adGroupId", row.adGroupId());
      node.put("adGroupName", row.adGroupName());
      node.put("campaignId", row.campaignId());
      node.put("campaignName", row.campaignName());
      node.put("impressions", row.impressions());
      node.put("clicks", row.clicks());
      node.put("cost", Money.majorUnits(row.costCents()));
      node.put("ctr", row.ctr());
      node.put("conversions", row.conversions());
      node.put("conversionsValue", Money.majorUnits(row.conversionsValueCents()));

      text.append("• ")
          .append(row.audienceName() == null ? row.audienceType() : row.audienceName())
          .append(" (")
          .append(row.adGroupName())
          .append("): ")
          .append(Money.display(row.costCents(), currency))
          .append(" spent, ")
          .append(row.clicks())
          .append(" clicks, ")
          .append(row.conversions())
          .append(" conversions\n");
    }
    if (rows.isEmpty()) {
      text.append("(no audience data — this account may not attach audiences to its ad groups)");
    }
    if (truncated) {
      text.append("(capped at ")
          .append(rows.size())
          .append(" audiences by spend — more exist; narrow by campaign_id or ad_group_id)");
    }
    return ToolResult.ok(text.toString(), structured);
  }
}
