package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleKeywordDto;
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

// Pauses, resumes, or re-bids one keyword — the smallest optimization step there is,
// and the one google_set_status cannot do, because a keyword is a criterion inside an
// ad group rather than an object with its own status switch.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateKeywordTool implements AdsTool {

  private static final List<String> STATUSES = List.of("ENABLED", "PAUSED");

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_keyword";
  }

  @Override
  public String description() {
    return "Pause or resume a single Google Ads keyword and/or change its max CPC bid. Pausing a"
        + " keyword stops it serving without deleting its history — prefer it to"
        + " google_remove_keywords when the keyword may come back. A bid only affects delivery"
        + " when the campaign uses Manual CPC; the result says so when it does not. Find"
        + " criterion ids with google_list_keywords.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "ad_group_id", "string", "Ad group the keyword sits in.");
    McpSchemas.prop(
        schema,
        "criterion_id",
        "string",
        "Criterion id of the keyword, from google_list_keywords.");
    ObjectNode status =
        McpSchemas.prop(
            schema, "status", "string", "PAUSED to stop it serving, ENABLED to resume.");
    McpSchemas.enumValues(status, STATUSES);
    McpSchemas.prop(
        schema,
        "cpc_bid",
        "number",
        "New max CPC bid in the account's currency units. Not allowed on negative keywords.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "ad_group_id", "criterion_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "criterionId", "string", "Criterion that was updated.");
    McpSchemas.prop(schema, "adGroupId", "string", "Ad group it sits in.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that ad group belongs to.");
    McpSchemas.nullableProp(schema, "text", "string", "Keyword text.");
    McpSchemas.prop(schema, "status", "string", "Status now in effect.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "cpcBid", "CPC bid, null when it was not changed.");
    McpSchemas.nullableProp(
        schema,
        "biddingStrategyType",
        "string",
        "Bidding strategy of the campaign; manual CPC bids only take effect under MANUAL_CPC.");
    McpSchemas.required(schema, "criterionId", "adGroupId", "campaignId", "status");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: enabling a keyword or raising its bid resumes and accelerates
    // spend. Idempotent: the call sets an absolute state, so repeating it changes
    // nothing further.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("ad_group_id") || !args.hasNonNull("criterion_id")) {
      throw new McpToolException("ad_group_id and criterion_id are required.");
    }
    if (!args.hasNonNull("status") && !args.hasNonNull("cpc_bid")) {
      throw new McpToolException("Provide status, cpc_bid, or both — there is nothing to change.");
    }
    String adGroupId = args.get("ad_group_id").asText();
    String criterionId = args.get("criterion_id").asText();
    String status = args.hasNonNull("status") ? args.get("status").asText().toUpperCase() : null;
    if (status != null && !STATUSES.contains(status)) {
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
    GoogleKeywordDto keyword =
        support.requireKeyword(
            support.keywordsOf(connection, token, customerId, loginCustomerId, adGroupId),
            criterionId,
            adGroupId);
    GoogleCampaignDto campaign =
        support.requireCampaign(
            connection, token, customerId, loginCustomerId, adGroup.campaignId());

    Long bidCents = null;
    if (args.hasNonNull("cpc_bid")) {
      if (keyword.negative()) {
        throw new McpToolException(
            "Keyword \""
                + keyword.text()
                + "\" is a negative keyword, so it has no CPC bid — it never gets clicked.");
      }
      bidCents = support.requireSaneBidCents(args.get("cpc_bid").asDouble(), campaign, currency);
    }

    String argsSummary =
        "adGroup="
            + adGroupId
            + ";criterion="
            + criterionId
            + ";status="
            + status
            + ";bidCents="
            + bidCents;
    final Long finalBidCents = bidCents;
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateKeyword(
                    token,
                    customerId,
                    loginCustomerId,
                    adGroupId,
                    criterionId,
                    status,
                    finalBidCents == null ? null : GoogleAdsApiClient.centsToMicros(finalBidCents));
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, criterionId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_keyword</b>\nUser: "
              + userId
              + "\nKeyword: "
              + keyword.text()
              + " ("
              + criterionId
              + ")\nStatus: "
              + (status == null ? "unchanged" : status)
              + "\nBid: "
              + (finalBidCents == null ? "unchanged" : Money.display(finalBidCents, currency)));

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("criterionId", criterionId);
      structured.put("adGroupId", adGroupId);
      structured.put("campaignId", adGroup.campaignId());
      structured.put("text", keyword.text());
      structured.put("status", status == null ? keyword.status() : status);
      structured.put("currency", currency);
      if (finalBidCents == null) {
        structured.putNull("cpcBid");
      } else {
        structured.put("cpcBid", Money.majorUnits(finalBidCents));
      }
      structured.put("biddingStrategyType", campaign.biddingStrategyType());

      StringBuilder text =
          new StringBuilder("Keyword \"" + keyword.text() + "\" (" + criterionId + ")");
      if (status != null) {
        text.append(" is now ").append(status);
      }
      if (finalBidCents != null) {
        text.append(status != null ? " and its" : " has its")
            .append(" CPC bid set to ")
            .append(Money.display(finalBidCents, currency));
      }
      text.append(".");
      if (finalBidCents != null) {
        text.append(support.biddingStrategyWarning(campaign));
      }
      return ToolResult.ok(text.toString(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, criterionId, false, e.getMessage());
      throw e;
    }
  }
}
