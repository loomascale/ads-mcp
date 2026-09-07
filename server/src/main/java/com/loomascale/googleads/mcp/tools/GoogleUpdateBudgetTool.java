package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.guardrail.BudgetGuardrailService;
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

// Sets the daily budget on a Google campaign's budget resource. Always
// guardrail-checked; refuses shared budgets, which govern several campaigns at once.
@Component
@RequiredArgsConstructor
public class GoogleUpdateBudgetTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_budget";
  }

  @Override
  public String description() {
    return "Change the daily budget of a Google Ads campaign. Cannot exceed your configured daily"
        + " spend cap, and refuses budgets shared across several campaigns. If you also run Meta"
        + " ads, that server exposes meta_update_budget.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign whose budget to update.");
    McpSchemas.prop(
        schema, "daily_budget", "number", McpSchemas.moneyInputDescription("New daily budget."));
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id", "daily_budget");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign whose budget was updated.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "dailyBudget", "New daily budget.");
    McpSchemas.required(schema, "campaignId", "dailyBudget");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("campaign_id") || !args.hasNonNull("daily_budget")) {
      throw new McpToolException("campaign_id and daily_budget are required.");
    }
    String campaignId = args.get("campaign_id").asText();
    long budgetCents = Money.minorUnits(args.get("daily_budget").asDouble());

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    // The cap and the request are both in the account currency, so resolve it
    // before the guardrail can refuse — the refusal message has to name it.
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);
    guardrail.enforce(connection, budgetCents, currency);

    List<GoogleCampaignDto> campaigns =
        adsService.call(
            connection,
            () -> adsService.client().listCampaigns(token, customerId, loginCustomerId));
    GoogleCampaignDto campaign =
        campaigns.stream()
            .filter(c -> campaignId.equals(c.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new McpToolException(
                        "Campaign "
                            + campaignId
                            + " was not found in account "
                            + customerId
                            + ". Use google_list_campaigns to see campaigns."));
    if (campaign.budgetExplicitlyShared()) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" uses a shared budget that also governs other campaigns, so this server will"
              + " not change it here. Adjust it in Google Ads directly.");
    }
    if (campaign.budgetResourceName() == null) {
      throw new McpToolException(
          "Campaign " + campaignId + " has no editable daily budget resource.");
    }

    String argsSummary = "campaign=" + campaignId + ";cents=" + budgetCents;
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateCampaignBudget(
                    token,
                    customerId,
                    loginCustomerId,
                    campaign.budgetResourceName(),
                    GoogleAdsApiClient.centsToMicros(budgetCents));
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_budget</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaignId
              + "\nNew daily budget: "
              + Money.display(budgetCents, currency));
      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("campaignId", campaignId);
      structured.put("currency", currency);
      structured.put("dailyBudget", Money.majorUnits(budgetCents));
      return ToolResult.ok(
          "Updated daily budget to "
              + Money.display(budgetCents, currency)
              + " for Google campaign "
              + campaignId
              + ".",
          structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, false, e.getMessage());
      throw e;
    }
  }
}
