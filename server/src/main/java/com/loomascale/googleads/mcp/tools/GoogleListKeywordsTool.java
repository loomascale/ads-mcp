package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleKeywordDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// The keyword list behind a Google Ads campaign, with the three status fields that
// explain a campaign delivering nothing: `status` (what the advertiser set),
// `systemServingStatus` (RARELY_SERVED = too little search volume) and
// `primaryStatus`/`primaryStatusReasons` (Google's own eligibility verdict).
// Meta has no equivalent — its targeting is interest-based, not keyword-based.
@Component
@RequiredArgsConstructor
public class GoogleListKeywordsTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_keywords";
  }

  @Override
  public String description() {
    return "List the keywords of a Google Ads account, campaign, or ad group with their match"
        + " type, status, serving status, eligibility reasons, quality score, and effective CPC"
        + " bid. Use this to explain why a campaign gets no impressions: a keyword can be enabled"
        + " and still never serve. Negative keywords are included and flagged. For per-keyword"
        + " performance numbers use google_get_insights with level=keyword; for the queries users"
        + " actually typed use google_list_search_terms; for new keyword suggestions use"
        + " google_keyword_ideas.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(schema, "campaign_id", "string", "Only keywords in this campaign.");
    McpSchemas.prop(schema, "ad_group_id", "string", "Only keywords in this ad group.");
    McpSchemas.prop(
        schema,
        "include_negative",
        "boolean",
        "Include negative (excluded) keywords. Default true.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the keywords belong to.");
    ObjectNode keyword =
        McpSchemas.objectArrayProp(schema, "keywords", "One row per keyword criterion.");
    McpSchemas.prop(keyword, "criterionId", "string", "Criterion id of the keyword.");
    McpSchemas.nullableProp(keyword, "text", "string", "The keyword text.");
    McpSchemas.nullableProp(keyword, "matchType", "string", "EXACT, PHRASE, or BROAD match type.");
    McpSchemas.nullableProp(
        keyword, "status", "string", "What the advertiser set: ENABLED or PAUSED.");
    McpSchemas.prop(
        keyword, "negative", "boolean", "True when this is an excluded keyword, not a target.");
    McpSchemas.nullableProp(
        keyword,
        "systemServingStatus",
        "string",
        "Whether Google will serve it. RARELY_SERVED means too little search volume.");
    McpSchemas.nullableProp(
        keyword, "primaryStatus", "string", "Google's overall eligibility verdict.");
    McpSchemas.stringArrayProp(
        keyword,
        "primaryStatusReasons",
        "Why the keyword is in that state, e.g. AD_GROUP_PAUSED or CAMPAIGN_REMOVED.");
    McpSchemas.nullableProp(
        keyword,
        "qualityScore",
        "integer",
        "Quality score 1-10, null until the keyword has enough impressions.");
    McpSchemas.moneyProp(
        keyword, "cpcBid", "Effective CPC bid, null for negatives and automated bidding.");
    McpSchemas.nullableProp(keyword, "adGroupId", "string", "Ad group the keyword sits in.");
    McpSchemas.nullableProp(keyword, "adGroupName", "string", "Name of that ad group.");
    McpSchemas.nullableProp(keyword, "campaignId", "string", "Campaign the ad group sits in.");
    McpSchemas.prop(schema, "keywordCount", "integer", "Number of keywords returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "keywords", "keywordCount");
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

    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;
    String adGroupId = args.hasNonNull("ad_group_id") ? args.get("ad_group_id").asText() : null;
    boolean includeNegative =
        !args.has("include_negative") || args.get("include_negative").asBoolean(true);

    List<GoogleKeywordDto> keywords =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listKeywords(token, customerId, loginCustomerId, campaignId, adGroupId));

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    ArrayNode arr = structured.putArray("keywords");
    StringBuilder text = new StringBuilder("Google Ads keywords for " + customerId + ":\n");
    int returned = 0;
    for (GoogleKeywordDto keyword : keywords) {
      if (keyword.negative() && !includeNegative) {
        continue;
      }
      returned++;
      ObjectNode node = arr.addObject();
      node.put("criterionId", keyword.criterionId());
      node.put("text", keyword.text());
      node.put("matchType", keyword.matchType());
      node.put("status", keyword.status());
      node.put("negative", keyword.negative());
      node.put("systemServingStatus", keyword.systemServingStatus());
      node.put("primaryStatus", keyword.primaryStatus());
      ArrayNode reasons = node.putArray("primaryStatusReasons");
      keyword.primaryStatusReasons().forEach(reasons::add);
      if (keyword.qualityScore() == null) {
        node.putNull("qualityScore");
      } else {
        node.put("qualityScore", keyword.qualityScore());
      }
      if (keyword.cpcBidCents() == null) {
        node.putNull("cpcBid");
      } else {
        node.put("cpcBid", Money.majorUnits(keyword.cpcBidCents()));
      }
      node.put("adGroupId", keyword.adGroupId());
      node.put("adGroupName", keyword.adGroupName());
      node.put("campaignId", keyword.campaignId());

      text.append("• ")
          .append(keyword.negative() ? "[negative] " : "")
          .append(keyword.text())
          .append(" (")
          .append(keyword.matchType())
          .append(") — ")
          .append(keyword.status())
          .append(serving(keyword))
          .append(keyword.qualityScore() == null ? "" : ", quality " + keyword.qualityScore())
          .append(", ad group ")
          .append(keyword.adGroupName())
          .append("\n");
    }
    structured.put("keywordCount", returned);
    if (returned == 0) {
      text.append("(no keywords — a search campaign with no keywords cannot serve)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  // The delivery-blocking part, spelled out in the text so it is not missed.
  private String serving(GoogleKeywordDto keyword) {
    StringBuilder sb = new StringBuilder();
    if (keyword.systemServingStatus() != null
        && !"ELIGIBLE".equals(keyword.systemServingStatus())) {
      sb.append(", serving ").append(keyword.systemServingStatus());
    }
    if (!keyword.primaryStatusReasons().isEmpty()) {
      sb.append(", because ").append(String.join("/", keyword.primaryStatusReasons()));
    }
    return sb.toString();
  }
}
