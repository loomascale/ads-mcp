package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleKeywordCreateSpec;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Adds keyword criteria to an ad group that already exists — the piece
// google_create_campaign cannot do, since that tool only ever builds a brand new
// campaign. Positive and negative keywords go through the same call; negatives are
// how you stop a wasteful search term found with google_list_search_terms.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleAddKeywordsTool implements AdsTool {

  static final int MAX_KEYWORDS = 50;
  private static final List<String> MATCH_TYPES = GoogleAdsEditSupport.MATCH_TYPES;
  private static final List<String> STATUSES = GoogleAdsEditSupport.STATUSES;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_add_keywords";
  }

  @Override
  public String description() {
    return "Add keywords to an existing Google Ads ad group, each with its own match type and"
        + " optional CPC bid. Set negative=true to exclude a search term instead of targeting it."
        + " Google cannot move a keyword between ad groups: remove it with"
        + " google_remove_keywords and add it here instead, which resets its quality score and"
        + " history. Up to "
        + MAX_KEYWORDS
        + " keywords per call.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "ad_group_id", "string", "Ad group the keywords are added to.");
    ObjectNode item =
        McpSchemas.objectArrayProp(
            schema, "keywords", "Keywords to add, at most " + MAX_KEYWORDS + " per call.");
    McpSchemas.prop(item, "text", "string", "Keyword text.");
    ObjectNode matchType =
        McpSchemas.prop(item, "match_type", "string", "Match type. Defaults to BROAD.");
    McpSchemas.enumValues(matchType, MATCH_TYPES);
    McpSchemas.prop(
        item,
        "cpc_bid",
        "number",
        "Max CPC bid in the account's currency units. Not allowed on negative keywords.");
    McpSchemas.prop(
        item, "negative", "boolean", "True to exclude this keyword instead of targeting it.");
    McpSchemas.required(item, "text");
    ObjectNode status =
        McpSchemas.prop(
            schema,
            "status",
            "string",
            "Status for every keyword added by this call. Defaults to ENABLED.");
    McpSchemas.enumValues(status, STATUSES);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "ad_group_id", "keywords");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "adGroupId", "string", "Ad group the keywords were added to.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that ad group belongs to.");
    McpSchemas.prop(schema, "addedCount", "integer", "Number of keywords created.");
    ObjectNode keyword =
        McpSchemas.objectArrayProp(schema, "keywords", "One row per created keyword.");
    McpSchemas.nullableProp(keyword, "criterionId", "string", "Criterion id of the new keyword.");
    McpSchemas.prop(keyword, "text", "string", "Keyword text.");
    McpSchemas.prop(keyword, "matchType", "string", "Match type it was created with.");
    McpSchemas.prop(keyword, "negative", "boolean", "True when it excludes rather than targets.");
    McpSchemas.prop(keyword, "status", "string", "Status it was created with.");
    McpSchemas.moneyProp(keyword, "cpcBid", "CPC bid, null when none was set.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.nullableProp(
        schema,
        "biddingStrategyType",
        "string",
        "Bidding strategy of the campaign; manual CPC bids only take effect under MANUAL_CPC.");
    McpSchemas.required(schema, "adGroupId", "campaignId", "addedCount", "keywords");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: a new keyword in a live ad group starts serving and spending
    // immediately. Not idempotent: a repeat call creates duplicate criteria.
    return ToolAnnotations.write(true, false);
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
    if (!args.has("keywords")
        || !args.get("keywords").isArray()
        || args.get("keywords").isEmpty()) {
      throw new McpToolException("keywords must contain at least one entry.");
    }
    if (args.get("keywords").size() > MAX_KEYWORDS) {
      throw new McpToolException("keywords must contain at most " + MAX_KEYWORDS + " entries.");
    }
    String adGroupId = args.get("ad_group_id").asText();
    String status =
        args.hasNonNull("status") ? args.get("status").asText().toUpperCase() : "ENABLED";
    if (!STATUSES.contains(status)) {
      throw new McpToolException("status must be one of: " + String.join(", ", STATUSES));
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleAdGroupDto adGroup =
        support.requireAdGroup(connection, token, customerId, loginCustomerId, adGroupId);
    GoogleCampaignDto campaign =
        support.requireCampaign(
            connection, token, customerId, loginCustomerId, adGroup.campaignId());

    List<GoogleKeywordCreateSpec> specs =
        support.parseKeywordSpecs(args.get("keywords"), status, campaign, MAX_KEYWORDS, currency);
    boolean anyBid = specs.stream().anyMatch(spec -> spec.cpcBidMicros() != null);

    String argsSummary = "adGroup=" + adGroupId + ";keywords=" + specs.size() + ";status=" + status;
    try {
      String adGroupResourceName = "customers/" + customerId + "/adGroups/" + adGroupId;
      List<String> resourceNames =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createKeywords(
                          token, customerId, loginCustomerId, adGroupResourceName, specs));
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, adGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_add_keywords</b>\nUser: "
              + userId
              + "\nAd group: "
              + adGroup.name()
              + " ("
              + adGroupId
              + ")\nKeywords: "
              + specs.size()
              + " ("
              + status
              + ")");

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("adGroupId", adGroupId);
      structured.put("currency", currency);
      structured.put("campaignId", adGroup.campaignId());
      structured.put("addedCount", specs.size());
      ArrayNode arr = structured.putArray("keywords");
      StringBuilder text =
          new StringBuilder(
              "Added "
                  + specs.size()
                  + " keyword"
                  + (specs.size() == 1 ? "" : "s")
                  + " to ad group "
                  + adGroup.name()
                  + " ("
                  + adGroupId
                  + ") as "
                  + status
                  + ":\n");
      for (int i = 0; i < specs.size(); i++) {
        GoogleKeywordCreateSpec spec = specs.get(i);
        ObjectNode node = arr.addObject();
        node.put("criterionId", criterionId(resourceNames, i));
        node.put("text", spec.text());
        node.put("matchType", spec.matchType());
        node.put("negative", spec.negative());
        node.put("status", spec.status());
        if (spec.cpcBidMicros() == null) {
          node.putNull("cpcBid");
        } else {
          node.put(
              "cpcBid", Money.majorUnits(GoogleAdsApiClient.microsToCents(spec.cpcBidMicros())));
        }
        text.append("• ")
            .append(spec.negative() ? "[negative] " : "")
            .append(spec.text())
            .append(" (")
            .append(spec.matchType())
            .append(")")
            .append(
                spec.cpcBidMicros() == null
                    ? ""
                    : ", bid "
                        + Money.display(
                            GoogleAdsApiClient.microsToCents(spec.cpcBidMicros()), currency))
            .append("\n");
      }
      structured.put("biddingStrategyType", campaign.biddingStrategyType());
      if (anyBid) {
        text.append(support.biddingStrategyWarning(campaign));
      }
      return ToolResult.ok(text.toString().trim(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, adGroupId, false, e.getMessage());
      throw e;
    }
  }

  // Google returns the created resource names in request order; a short response is
  // not worth failing the call over, so the id is simply reported as null.
  private String criterionId(List<String> resourceNames, int index) {
    if (index >= resourceNames.size() || resourceNames.get(index) == null) {
      return null;
    }
    String resourceName = resourceNames.get(index);
    int tilde = resourceName.indexOf('~');
    return tilde < 0
        ? resourceName.substring(resourceName.lastIndexOf('/') + 1)
        : resourceName.substring(tilde + 1);
  }
}
