package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
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

// Adds an ad group to a campaign that already exists — the missing half of
// restructuring, since google_create_campaign only ever builds one ad group inside a
// brand new campaign. The ad group is created PAUSED and has no ad yet, so it cannot
// spend: the caller adds an ad in Google Ads and then enables it with
// google_set_status.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCreateAdGroupTool implements AdsTool {

  static final int MAX_KEYWORDS = 50;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_create_ad_group";
  }

  @Override
  public String description() {
    return "Create an ad group inside an existing Google Ads Search campaign, optionally with"
        + " keywords and a default CPC bid. The ad group is created PAUSED and without ads, so it"
        + " cannot spend until an ad exists and google_set_status enables it. Use this to split a"
        + " bloated ad group into tighter themes; Google cannot move keywords, so the ones that"
        + " belong here are re-created with google_add_keywords and dropped from the old ad group"
        + " with google_remove_keywords, losing their history.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign the ad group is created in.");
    McpSchemas.prop(schema, "name", "string", "Ad group name.");
    McpSchemas.prop(
        schema,
        "cpc_bid",
        "number",
        "Default max CPC bid for the ad group, in the account's currency units.");
    ObjectNode item =
        McpSchemas.objectArrayProp(
            schema,
            "keywords",
            "Optional keywords to create in the new ad group, at most " + MAX_KEYWORDS + ".");
    McpSchemas.prop(item, "text", "string", "Keyword text.");
    ObjectNode matchType =
        McpSchemas.prop(item, "match_type", "string", "Match type. Defaults to BROAD.");
    McpSchemas.enumValues(matchType, GoogleAdsEditSupport.MATCH_TYPES);
    McpSchemas.prop(
        item,
        "cpc_bid",
        "number",
        "Max CPC bid for this keyword. Not allowed on negative keywords.");
    McpSchemas.prop(
        item, "negative", "boolean", "True to exclude this keyword instead of targeting it.");
    McpSchemas.required(item, "text");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id", "name");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "adGroupId", "string", "Id of the ad group that was created.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign it was created in.");
    McpSchemas.prop(schema, "name", "string", "Name of the new ad group.");
    ObjectNode status =
        McpSchemas.prop(
            schema, "status", "string", "Always PAUSED — nothing spends until it is enabled.");
    McpSchemas.enumValues(status, List.of("PAUSED"));
    McpSchemas.prop(schema, "keywordCount", "integer", "Number of keywords created inside it.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "cpcBid", "Default CPC bid, null when none was set.");
    McpSchemas.nullableProp(
        schema,
        "biddingStrategyType",
        "string",
        "Bidding strategy of the campaign; manual CPC bids only take effect under MANUAL_CPC.");
    McpSchemas.required(schema, "adGroupId", "campaignId", "name", "status", "keywordCount");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Not destructive: the ad group is paused and adless, so it cannot spend. Not
    // idempotent: calling twice creates two ad groups.
    return ToolAnnotations.write(false, false);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("campaign_id") || !args.hasNonNull("name")) {
      throw new McpToolException("campaign_id and name are required.");
    }
    String campaignId = args.get("campaign_id").asText();
    String adGroupName = args.get("name").asText().trim();
    if (adGroupName.isBlank()) {
      throw new McpToolException("name must not be blank.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleCampaignDto campaign =
        support.requireCampaign(connection, token, customerId, loginCustomerId, campaignId);
    Long bidCents =
        args.hasNonNull("cpc_bid")
            ? support.requireSaneBidCents(args.get("cpc_bid").asDouble(), campaign, currency)
            : null;
    List<GoogleKeywordCreateSpec> keywords =
        args.has("keywords") && !args.get("keywords").isEmpty()
            ? support.parseKeywordSpecs(
                args.get("keywords"), "ENABLED", campaign, MAX_KEYWORDS, currency)
            : List.of();

    String argsSummary =
        "campaign=" + campaignId + ";name=" + adGroupName + ";keywords=" + keywords.size();
    String adGroupResourceName = null;
    try {
      String campaignResourceName = "customers/" + customerId + "/campaigns/" + campaignId;
      adGroupResourceName =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createAdGroup(
                          token,
                          customerId,
                          loginCustomerId,
                          adGroupName,
                          campaignResourceName,
                          bidCents == null ? null : GoogleAdsApiClient.centsToMicros(bidCents),
                          "PAUSED"));
      String adGroupId = adGroupResourceName.substring(adGroupResourceName.lastIndexOf('/') + 1);

      if (!keywords.isEmpty()) {
        String createdResourceName = adGroupResourceName;
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .createKeywords(
                        token, customerId, loginCustomerId, createdResourceName, keywords));
      }

      audit.record(userId, name(), WriteKind.CREATE, argsSummary, adGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_create_ad_group</b>\nUser: "
              + userId
              + "\nAd group: "
              + adGroupName
              + " ("
              + adGroupId
              + ", PAUSED)\nCampaign: "
              + campaign.name()
              + "\nKeywords: "
              + keywords.size());

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("adGroupId", adGroupId);
      structured.put("campaignId", campaignId);
      structured.put("name", adGroupName);
      structured.put("status", "PAUSED");
      structured.put("keywordCount", keywords.size());
      structured.put("currency", currency);
      if (bidCents == null) {
        structured.putNull("cpcBid");
      } else {
        structured.put("cpcBid", Money.majorUnits(bidCents));
      }
      structured.put("biddingStrategyType", campaign.biddingStrategyType());

      StringBuilder text =
          new StringBuilder(
              "Created PAUSED ad group \""
                  + adGroupName
                  + "\" (id "
                  + adGroupId
                  + ") in campaign "
                  + campaign.name()
                  + " with "
                  + keywords.size()
                  + " keyword"
                  + (keywords.size() == 1 ? "" : "s")
                  + ". It has no ads yet, so it cannot serve: add an ad, then enable it with"
                  + " google_set_status.");
      if (bidCents != null) {
        text.append(" Default CPC bid ")
            .append(Money.display(bidCents, currency))
            .append(".")
            .append(support.biddingStrategyWarning(campaign));
      }
      return ToolResult.ok(text.toString(), structured);
    } catch (RuntimeException e) {
      // Best-effort rollback: an ad group whose keywords failed is litter.
      if (adGroupResourceName != null) {
        try {
          adsService
              .client()
              .removeResource(token, customerId, loginCustomerId, adGroupResourceName);
        } catch (RuntimeException rollbackFailure) {
          log.warn("Rollback failed for {}: {}", adGroupResourceName, rollbackFailure.getMessage());
        }
      }
      audit.record(userId, name(), WriteKind.CREATE, argsSummary, null, false, e.getMessage());
      throw e;
    }
  }
}
