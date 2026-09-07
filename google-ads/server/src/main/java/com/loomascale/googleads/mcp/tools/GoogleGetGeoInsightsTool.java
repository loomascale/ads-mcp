package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleGeoInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleGeoRowDto;
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
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Performance by location — the segment that most often hides waste, because a
// campaign targeting a whole country pays the same bid everywhere while converting
// in a handful of places. Location ids are resolved to names in a second query so
// the rows are readable. Money is in minor units of the account currency.
@Component
@RequiredArgsConstructor
public class GoogleGetGeoInsightsTool implements AdsTool {

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
    return "google_get_geo_insights";
  }

  @Override
  public String description() {
    return "Get Google Ads performance broken down by location: impressions, clicks, cost, CTR,"
        + " conversions and conversion value per geographic area, ordered by spend, with the"
        + " location named rather than left as an id. Use this to find regions that spend without"
        + " converting. For breakdowns by device, day of week, hour or network use"
        + " google_get_insights with its segments argument; for audiences use"
        + " google_get_audience_insights.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(schema, "campaign_id", "string", "Only this campaign.");
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
    ObjectNode row = McpSchemas.objectArrayProp(schema, "rows", "One row per location.");
    McpSchemas.nullableProp(row, "locationId", "string", "Google geo target id for the location.");
    McpSchemas.nullableProp(
        row,
        "locationName",
        "string",
        "Readable location, e.g. \"Kyiv,Kyiv city,Ukraine\". Null when the id could not be named.");
    McpSchemas.nullableProp(
        row,
        "locationType",
        "string",
        "Whether the row counts where the user physically was or the area they showed interest in.");
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
        "True when the row cap was reached, so more locations exist than were returned.");
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
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : 0;

    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleGeoInsightsQuery query =
        new GoogleGeoInsightsQuery(campaignId, preset, since, until, limit);
    List<GoogleGeoRowDto> rows =
        adsService.call(
            connection,
            () -> adsService.client().getGeoInsights(token, customerId, loginCustomerId, query));
    boolean truncated = rows.size() >= GoogleAdsApiClient.clampLimit(limit);

    // A row per numeric location id is unreadable, so name them — one extra query
    // for the ids this report actually returned.
    List<String> locationIds = new ArrayList<>();
    for (GoogleGeoRowDto row : rows) {
      if (row.locationId() != null && !locationIds.contains(row.locationId())) {
        locationIds.add(row.locationId());
      }
    }
    Map<String, String> names =
        locationIds.isEmpty()
            ? Map.of()
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .resolveGeoTargetNames(token, customerId, loginCustomerId, locationIds));

    String range = preset != null ? preset : since + ".." + until;
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("dateRange", range);
    structured.put("truncated", truncated);
    ArrayNode arr = structured.putArray("rows");
    StringBuilder text =
        new StringBuilder(
            "Google Ads performance by location for " + customerId + " (" + range + "):\n");

    for (GoogleGeoRowDto row : rows) {
      String name = names.get(row.locationId());
      ObjectNode node = arr.addObject();
      node.put("locationId", row.locationId());
      node.put("locationName", name);
      node.put("locationType", row.locationType());
      node.put("campaignId", row.campaignId());
      node.put("campaignName", row.campaignName());
      node.put("impressions", row.impressions());
      node.put("clicks", row.clicks());
      node.put("cost", Money.majorUnits(row.costCents()));
      node.put("ctr", row.ctr());
      node.put("conversions", row.conversions());
      node.put("conversionsValue", Money.majorUnits(row.conversionsValueCents()));

      text.append("• ")
          .append(name == null ? "Location " + row.locationId() : name)
          .append(": ")
          .append(Money.display(row.costCents(), currency))
          .append(" spent, ")
          .append(row.clicks())
          .append(" clicks, ")
          .append(row.conversions())
          .append(" conversions\n");
    }
    if (rows.isEmpty()) {
      text.append("(no location data for this range)");
    }
    if (truncated) {
      text.append("(capped at ")
          .append(rows.size())
          .append(" locations by spend — more exist; narrow by campaign_id)");
    }
    return ToolResult.ok(text.toString(), structured);
  }
}
