package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Renames an ad group and/or sets its default CPC bid — the bid every keyword in it
// falls back to when it has none of its own. Pausing and resuming an ad group stays
// in google_set_status.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateAdGroupTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_ad_group";
  }

  @Override
  public String description() {
    return "Rename a Google Ads ad group and/or set its default max CPC bid, which applies to"
        + " every keyword in it that has no bid of its own. A bid only affects delivery when the"
        + " campaign uses Manual CPC; the result says so when it does not. To pause or resume the"
        + " ad group use google_set_status, and to change its keywords use google_add_keywords,"
        + " google_update_keyword, or google_remove_keywords.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "ad_group_id", "string", "Ad group to update.");
    McpSchemas.prop(schema, "name", "string", "New ad group name.");
    McpSchemas.prop(
        schema, "cpc_bid", "number", "New default max CPC bid in the account's currency units.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "ad_group_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "adGroupId", "string", "Ad group that was updated.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign it belongs to.");
    McpSchemas.prop(schema, "name", "string", "Name now in effect.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "cpcBid", "Default CPC bid, null when the ad group has none.");
    McpSchemas.nullableProp(
        schema,
        "biddingStrategyType",
        "string",
        "Bidding strategy of the campaign; manual CPC bids only take effect under MANUAL_CPC.");
    McpSchemas.required(schema, "adGroupId", "campaignId", "name");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: raising the default bid makes the ad group spend its campaign
    // budget faster. Idempotent: it sets absolute values, so a repeat call is a no-op.
    return ToolAnnotations.write(true, true);
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
    if (!args.hasNonNull("name") && !args.hasNonNull("cpc_bid")) {
      throw new McpToolException("Provide name, cpc_bid, or both — there is nothing to change.");
    }
    String adGroupId = args.get("ad_group_id").asText();
    String newName = args.hasNonNull("name") ? args.get("name").asText().trim() : null;
    if (newName != null && newName.isBlank()) {
      throw new McpToolException("name must not be blank.");
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
    Long bidCents =
        args.hasNonNull("cpc_bid")
            ? support.requireSaneBidCents(args.get("cpc_bid").asDouble(), campaign, currency)
            : null;

    String argsSummary = "adGroup=" + adGroupId + ";name=" + newName + ";bidCents=" + bidCents;
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateAdGroup(
                    token,
                    customerId,
                    loginCustomerId,
                    adGroupId,
                    newName,
                    bidCents == null ? null : GoogleAdsApiClient.centsToMicros(bidCents));
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, adGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_ad_group</b>\nUser: "
              + userId
              + "\nAd group: "
              + adGroup.name()
              + " ("
              + adGroupId
              + ")\nName: "
              + (newName == null ? "unchanged" : newName)
              + "\nBid: "
              + (bidCents == null ? "unchanged" : Money.display(bidCents, currency)));

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("adGroupId", adGroupId);
      structured.put("campaignId", adGroup.campaignId());
      structured.put("name", newName == null ? adGroup.name() : newName);
      structured.put("currency", currency);
      Long effectiveBid = bidCents == null ? adGroup.cpcBidCents() : bidCents;
      if (effectiveBid == null) {
        structured.putNull("cpcBid");
      } else {
        structured.put("cpcBid", Money.majorUnits(effectiveBid));
      }
      structured.put("biddingStrategyType", campaign.biddingStrategyType());

      StringBuilder text = new StringBuilder("Ad group " + adGroupId);
      if (newName != null) {
        text.append(" renamed from \"")
            .append(adGroup.name())
            .append("\" to \"")
            .append(newName)
            .append("\"");
      }
      if (bidCents != null) {
        text.append(newName != null ? " and its" : " has its")
            .append(" default CPC bid set to ")
            .append(Money.display(bidCents, currency));
      }
      text.append(".");
      if (bidCents != null) {
        text.append(support.biddingStrategyWarning(campaign));
      }
      return ToolResult.ok(text.toString(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, adGroupId, false, e.getMessage());
      throw e;
    }
  }
}
