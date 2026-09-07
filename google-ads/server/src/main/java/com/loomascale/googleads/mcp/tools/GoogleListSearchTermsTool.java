package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleSearchTermDto;
import com.loomascale.googleads.client.dto.GoogleSearchTermQuery;
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

// What people actually typed before clicking, which is never the same list as the
// keywords bid on. `status` says whether a term is already covered by a keyword
// (ADDED), already blocked (EXCLUDED), or neither (NONE) — the NONE rows are the
// ones worth acting on. Search terms only exist once ads have served, so an empty
// result on a zero-impression campaign is itself the answer.
@Component
@RequiredArgsConstructor
public class GoogleListSearchTermsTool implements AdsTool {

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
    return "google_list_search_terms";
  }

  @Override
  public String description() {
    return "List the actual search queries that triggered Google Ads ads, with impressions,"
        + " clicks, cost, CTR, conversions, the keyword each query matched, and whether the query"
        + " is already added as a keyword or excluded as a negative. Ordered by impressions and"
        + " capped. Use this to find wasted spend and missing keywords. For the keyword list"
        + " itself use google_list_keywords; for suggestions not yet in the account use"
        + " google_keyword_ideas. Meta has no search term equivalent.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(schema, "campaign_id", "string", "Only terms from this campaign.");
    McpSchemas.prop(schema, "ad_group_id", "string", "Only terms from this ad group.");
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
        schema, "limit", "integer", "Max terms, highest impressions first. Default 100, max 1000.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the terms belong to.");
    McpSchemas.prop(schema, "dateRange", "string", "Date range the report covers.");
    ObjectNode term =
        McpSchemas.objectArrayProp(schema, "searchTerms", "One row per search query.");
    McpSchemas.nullableProp(term, "searchTerm", "string", "The query the user typed.");
    McpSchemas.nullableProp(
        term,
        "status",
        "string",
        "ADDED when a keyword already covers it, EXCLUDED when a negative blocks it,"
            + " NONE when neither.");
    McpSchemas.nullableProp(
        term,
        "matchedKeywordText",
        "string",
        "The keyword the query matched, which differs from the query on broad and phrase match.");
    McpSchemas.nullableProp(term, "matchType", "string", "Match type of that keyword.");
    McpSchemas.nullableProp(term, "adGroupId", "string", "Ad group that served.");
    McpSchemas.nullableProp(term, "campaignId", "string", "Campaign that served.");
    McpSchemas.prop(term, "impressions", "integer", "Impressions.");
    McpSchemas.prop(term, "clicks", "integer", "Clicks.");
    McpSchemas.moneyProp(term, "cost", "Cost of the clicks this search term produced.");
    McpSchemas.prop(term, "ctr", "number", "Click-through rate (0-1).");
    McpSchemas.prop(term, "conversions", "number", "Conversions per the account's tracking setup.");
    McpSchemas.prop(
        schema,
        "truncated",
        "boolean",
        "True when the cap was reached, so more terms exist than were returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "dateRange", "searchTerms", "truncated");
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
    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

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

    GoogleSearchTermQuery query =
        new GoogleSearchTermQuery(preset, since, until, campaignId, adGroupId, limit);
    List<GoogleSearchTermDto> terms =
        adsService.call(
            connection,
            () -> adsService.client().listSearchTerms(token, customerId, loginCustomerId, query));
    boolean truncated = terms.size() >= GoogleAdsApiClient.clampLimit(limit);
    String range = preset != null ? preset : since + ".." + until;

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("dateRange", range);
    structured.put("truncated", truncated);
    ArrayNode arr = structured.putArray("searchTerms");
    StringBuilder text =
        new StringBuilder("Google Ads search terms for " + customerId + " (" + range + "):\n");
    for (GoogleSearchTermDto term : terms) {
      ObjectNode node = arr.addObject();
      node.put("searchTerm", term.searchTerm());
      node.put("status", term.status());
      node.put("matchedKeywordText", term.matchedKeywordText());
      node.put("matchType", term.matchType());
      node.put("adGroupId", term.adGroupId());
      node.put("campaignId", term.campaignId());
      node.put("impressions", term.impressions());
      node.put("clicks", term.clicks());
      node.put("cost", Money.majorUnits(term.costCents()));
      node.put("ctr", term.ctr());
      node.put("conversions", term.conversions());

      text.append("• \"")
          .append(term.searchTerm())
          .append("\" — ")
          .append(term.impressions())
          .append(" impressions, ")
          .append(term.clicks())
          .append(" clicks, ")
          .append(Money.display(term.costCents(), currency))
          .append(term.conversions() > 0 ? ", " + term.conversions() + " conversions" : "")
          .append(" (matched ")
          .append(term.matchedKeywordText())
          .append(", ")
          .append(term.status())
          .append(")\n");
    }
    if (terms.isEmpty()) {
      text.append(
          "(no search terms — the ads have not served for this range, so there is nothing to"
              + " report yet)");
    }
    if (truncated) {
      text.append("(capped at ")
          .append(terms.size())
          .append(" terms by impressions — more exist; narrow by campaign_id or raise limit)");
    }
    return ToolResult.ok(text.toString(), structured);
  }
}
