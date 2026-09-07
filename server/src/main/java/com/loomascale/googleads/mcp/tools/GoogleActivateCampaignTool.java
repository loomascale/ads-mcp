package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

// The explicit go-live for Google campaigns. Only activates campaigns this server
// itself created (per the audit log), and re-validates the daily budget against
// the cap before spend starts.
@Component
@RequiredArgsConstructor
public class GoogleActivateCampaignTool implements AdsTool {

  private static final int MAX_ACTIVATIONS_PER_DAY = 5;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_activate_campaign";
  }

  @Override
  public String description() {
    return "Activate a PAUSED Google Ads campaign so it starts spending. Only works on campaigns"
        + " created through this server, and re-checks the budget against your daily cap first."
        + " If you also run Meta ads, that server exposes meta_activate_campaign.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign id to activate (go live).");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was activated.");
    ObjectNode status =
        McpSchemas.prop(
            schema, "status", "string", "Always ENABLED — the campaign is now spending.");
    McpSchemas.enumValues(status, List.of("ENABLED"));
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "dailyBudget", "Daily budget now live for the campaign.");
    McpSchemas.required(schema, "campaignId", "status", "dailyBudget");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    return ToolAnnotations.write(true, false);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("campaign_id")) {
      throw new McpToolException("campaign_id is required.");
    }
    String campaignId = args.get("campaign_id").asText();

    if (!audit.wasCreatedByUs(userId, campaignId)) {
      throw new McpToolException(
          "For safety, only campaigns created through this server can be activated here. Activate"
              + " this one in Google Ads directly if it was created elsewhere.");
    }
    if (audit.countToday(userId, name()) >= MAX_ACTIVATIONS_PER_DAY) {
      throw new McpToolException(
          "Daily activation limit of " + MAX_ACTIVATIONS_PER_DAY + " reached. Try again tomorrow.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleCampaignDto campaign =
        adsService
            .call(
                connection,
                () -> adsService.client().listCampaigns(token, customerId, loginCustomerId))
            .stream()
            .filter(c -> campaignId.equals(c.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new McpToolException(
                        "Campaign "
                            + campaignId
                            + " was not found in account "
                            + customerId
                            + "."));
    if ("ENABLED".equals(campaign.status())) {
      throw new McpToolException(
          "Campaign " + campaignId + " is already active — nothing to activate.");
    }
    long budgetCents = campaign.dailyBudgetCents() != null ? campaign.dailyBudgetCents() : 0;
    if (budgetCents <= 0) {
      // No visible budget means the cap cannot be verified before spend starts, so
      // refuse rather than activating unchecked (shared budgets land here too).
      throw new McpToolException(
          "Could not determine the daily budget governing campaign "
              + campaignId
              + "; activate it in Google Ads directly.");
    }
    // Resolved before the guardrail runs so a refusal names the account currency.
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);
    guardrail.enforce(connection, budgetCents, currency);

    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .setCampaignStatus(token, customerId, loginCustomerId, campaignId, "ENABLED");
            return null;
          });
      audit.record(
          userId,
          name(),
          WriteKind.UPDATE,
          "campaign=" + campaignId + ";cents=" + budgetCents,
          campaignId,
          true,
          null);
      audit.alert(
          "<b>MCP Ads: google ACTIVATED</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaignId
              + "\nDaily budget now live: "
              + Money.display(budgetCents, currency));
      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("campaignId", campaignId);
      structured.put("status", "ENABLED");
      structured.put("currency", currency);
      structured.put("dailyBudget", Money.majorUnits(budgetCents));
      return ToolResult.ok(
          "Google campaign "
              + campaignId
              + " is now LIVE and will spend up to "
              + Money.display(budgetCents, currency)
              + "/day.",
          structured);
    } catch (RuntimeException e) {
      audit.record(
          userId,
          name(),
          WriteKind.UPDATE,
          "campaign=" + campaignId,
          campaignId,
          false,
          e.getMessage());
      throw e;
    }
  }
}
