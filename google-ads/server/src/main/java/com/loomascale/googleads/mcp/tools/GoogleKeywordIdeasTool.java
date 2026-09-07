package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleKeywordIdeaDto;
import com.loomascale.googleads.client.dto.GoogleKeywordIdeaSpec;
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

// Keyword research: search volume, competition, and top-of-page bid estimates for
// terms seeded from keywords, a landing page, or both. This is the only Google tool
// that reports on keywords the account does not have yet — everything else reads what
// is already there. Reads through KeywordPlanIdeaService, which creates nothing.
@Component
@RequiredArgsConstructor
public class GoogleKeywordIdeasTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_keyword_ideas";
  }

  @Override
  public String description() {
    return "Research new Google Ads keywords: average monthly search volume, competition level,"
        + " and low/high top-of-page bid estimates for terms seeded from keyword phrases, a landing"
        + " page url, or both. Use this to find terms worth adding and to check whether an existing"
        + " keyword has any search volume at all. Returns the first page of results only. For"
        + " keywords already in the account use google_list_keywords; for queries that already"
        + " triggered ads use google_list_search_terms.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.stringArrayProp(
        schema,
        "keywords",
        "Seed keywords or phrases. Required unless page_url is given; both may be set.");
    McpSchemas.prop(
        schema,
        "page_url",
        "string",
        "Landing page to seed ideas from. Required unless keywords are given.");
    McpSchemas.stringArrayProp(
        schema,
        "geo_target_ids",
        "Google geo target constant ids to report volume for, e.g. 2840 for the United States."
            + " Max 10. Omit for all locations.");
    McpSchemas.prop(
        schema,
        "language_id",
        "string",
        "Google language constant id, e.g. 1000 for English. Omit for all languages.");
    McpSchemas.prop(schema, "limit", "integer", "Max ideas. Default 100, max 1000.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the request was billed to.");
    ObjectNode idea = McpSchemas.objectArrayProp(schema, "ideas", "One row per suggested keyword.");
    McpSchemas.nullableProp(idea, "text", "string", "The suggested keyword.");
    McpSchemas.nullableProp(
        idea,
        "avgMonthlySearches",
        "integer",
        "Approximate monthly searches averaged over 12 months, null when Google has too little"
            + " data.");
    McpSchemas.nullableProp(
        idea, "competition", "string", "Competition bucket: LOW, MEDIUM, or HIGH.");
    McpSchemas.nullableProp(idea, "competitionIndex", "integer", "Same competition as 0-100.");
    McpSchemas.moneyProp(idea, "lowTopOfPageBid", "20th percentile top-of-page bid.");
    McpSchemas.moneyProp(idea, "highTopOfPageBid", "80th percentile top-of-page bid.");
    McpSchemas.prop(schema, "ideaCount", "integer", "Number of ideas returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "ideas", "ideaCount");
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

    List<String> keywords = textList(args, "keywords");
    String pageUrl = args.hasNonNull("page_url") ? args.get("page_url").asText() : null;
    // Checked before touching the API: Google rejects a seedless request anyway, and
    // the refusal is more useful than a translated INVALID_ARGUMENT.
    if (keywords.isEmpty() && (pageUrl == null || pageUrl.isBlank())) {
      throw new McpToolException("Provide keywords, page_url, or both to seed the keyword ideas.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleKeywordIdeaSpec spec =
        new GoogleKeywordIdeaSpec(
            keywords,
            pageUrl,
            textList(args, "geo_target_ids"),
            args.hasNonNull("language_id") ? args.get("language_id").asText() : null,
            args.hasNonNull("limit") ? args.get("limit").asInt() : 0);
    List<GoogleKeywordIdeaDto> ideas =
        adsService.call(
            connection,
            () ->
                adsService.client().generateKeywordIdeas(token, customerId, loginCustomerId, spec));

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("ideaCount", ideas.size());
    ArrayNode arr = structured.putArray("ideas");
    StringBuilder text = new StringBuilder("Google Ads keyword ideas for " + customerId + ":\n");
    for (GoogleKeywordIdeaDto idea : ideas) {
      ObjectNode node = arr.addObject();
      node.put("text", idea.text());
      putNullable(node, "avgMonthlySearches", idea.avgMonthlySearches());
      node.put("competition", idea.competition());
      if (idea.competitionIndex() == null) {
        node.putNull("competitionIndex");
      } else {
        node.put("competitionIndex", idea.competitionIndex());
      }
      putNullableMoney(node, "lowTopOfPageBid", idea.lowTopOfPageBidCents());
      putNullableMoney(node, "highTopOfPageBid", idea.highTopOfPageBidCents());

      text.append("• ")
          .append(idea.text())
          .append(" — ")
          .append(
              idea.avgMonthlySearches() == null
                  ? "no volume data"
                  : idea.avgMonthlySearches() + " searches/mo")
          .append(idea.competition() == null ? "" : ", " + idea.competition() + " competition")
          .append(bidRange(idea, currency))
          .append("\n");
    }
    if (ideas.isEmpty()) {
      text.append("(no ideas for this seed)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  private List<String> textList(JsonNode args, String field) {
    List<String> values = new ArrayList<>();
    if (args.hasNonNull(field) && args.get(field).isArray()) {
      for (JsonNode value : args.get(field)) {
        if (!value.asText().isBlank()) {
          values.add(value.asText());
        }
      }
    }
    return values;
  }

  private void putNullable(ObjectNode node, String field, Long value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private void putNullableMoney(ObjectNode node, String field, Long minorUnits) {
    if (minorUnits == null) {
      node.putNull(field);
    } else {
      node.put(field, Money.majorUnits(minorUnits));
    }
  }

  private String bidRange(GoogleKeywordIdeaDto idea, String currency) {
    if (idea.lowTopOfPageBidCents() == null && idea.highTopOfPageBidCents() == null) {
      return "";
    }
    return ", top-of-page bid "
        + money(idea.lowTopOfPageBidCents(), currency)
        + "-"
        + money(idea.highTopOfPageBidCents(), currency);
  }

  private String money(Long minorUnits, String currency) {
    return minorUnits == null ? "?" : Money.display(minorUnits, currency);
  }
}
