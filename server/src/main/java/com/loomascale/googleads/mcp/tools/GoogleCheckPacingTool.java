package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleInsightsRowDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Derived health check for Google Ads: today's spend per enabled campaign vs its
// daily budget, zero-delivery enabled campaigns, and the account-wide cap — the
// Google twin of meta_check_pacing. (Google's standard delivery can spend up to 2x the
// daily budget on a single day, so over_pacing here means "watch this", not a bug.)
@Component
@RequiredArgsConstructor
public class GoogleCheckPacingTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_check_pacing";
  }

  @Override
  public String description() {
    return "Check today's spend and delivery health for a Google Ads account: pacing against daily"
        + " budgets and enabled campaigns with zero delivery. If you also run Meta ads, that"
        + " server exposes meta_check_pacing.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account that was checked.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "totalSpentToday", "Total spend today across the account.");
    McpSchemas.prop(
        schema,
        "issueCount",
        "integer",
        "Number of problems found, including the account-wide cap breach that has no flag entry.");
    ObjectNode flag =
        McpSchemas.objectArrayProp(schema, "flags", "One entry per campaign-level problem.");
    McpSchemas.prop(flag, "campaignId", "string", "Campaign the problem was found on.");
    McpSchemas.nullableProp(flag, "campaignName", "string", "Campaign name.");
    ObjectNode type = McpSchemas.prop(flag, "type", "string", "Problem category.");
    McpSchemas.enumValues(type, List.of("zero_delivery", "over_pacing"));
    McpSchemas.prop(flag, "detail", "string", "Human-readable explanation of the problem.");
    McpSchemas.required(schema, "customerId", "totalSpentToday", "issueCount", "flags");
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

    List<GoogleCampaignDto> campaigns =
        adsService.call(
            connection,
            () -> adsService.client().listCampaigns(token, customerId, loginCustomerId));
    GoogleInsightsQuery todayQuery =
        new GoogleInsightsQuery("campaign", "TODAY", null, null, null, null, 0, List.of(), false);
    List<GoogleInsightsRowDto> today =
        adsService.call(
            connection,
            () -> adsService.client().getInsights(token, customerId, loginCustomerId, todayQuery));
    Map<String, GoogleInsightsRowDto> spendByCampaign = new HashMap<>();
    for (GoogleInsightsRowDto row : today) {
      if (row.entityId() != null) {
        spendByCampaign.put(row.entityId(), row);
      }
    }

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    ArrayNode flags = structured.putArray("flags");
    StringBuilder text =
        new StringBuilder("Pacing check for Google Ads account " + customerId + ":\n");
    int issues = 0;

    for (GoogleCampaignDto c : campaigns) {
      boolean enabled = "ENABLED".equals(c.status());
      GoogleInsightsRowDto spend = spendByCampaign.get(c.id());
      long spentCents = spend != null ? spend.costCents() : 0;

      // Zero delivery: enabled with a budget but no spend today.
      if (enabled && spentCents == 0 && c.dailyBudgetCents() != null && c.dailyBudgetCents() > 0) {
        issues++;
        addFlag(flags, c, "zero_delivery", "No spend today despite enabled budget");
        text.append("⚠ ")
            .append(c.name())
            .append(": ")
            .append(Money.display(0L, currency))
            .append(" spent today\n");
      }
      // Over-pacing: today's spend already exceeds the daily budget.
      if (c.dailyBudgetCents() != null
          && c.dailyBudgetCents() > 0
          && spentCents > c.dailyBudgetCents()) {
        issues++;
        addFlag(flags, c, "over_pacing", "Spend exceeds daily budget");
        text.append("⚠ ")
            .append(c.name())
            .append(": ")
            .append(Money.display(spentCents, currency))
            .append(" spent vs ")
            .append(Money.display(c.dailyBudgetCents(), currency))
            .append(" budget\n");
      }
    }

    // Account-wide spend vs the user's configured cap.
    long totalToday = today.stream().mapToLong(GoogleInsightsRowDto::costCents).sum();
    structured.put("totalSpentToday", Money.majorUnits(totalToday));
    if (connection.maxDailyBudgetMinorUnits() != null
        && totalToday > connection.maxDailyBudgetMinorUnits()) {
      issues++;
      text.append("⚠ Total spend today ")
          .append(Money.display(totalToday, currency))
          .append(" exceeds your daily cap ")
          .append(Money.display(connection.maxDailyBudgetMinorUnits(), currency))
          .append("\n");
    }

    if (issues == 0) {
      text.append("All enabled campaigns pacing normally. Total spent today: ")
          .append(Money.display(totalToday, currency));
    }
    structured.put("issueCount", issues);
    return ToolResult.ok(text.toString(), structured);
  }

  private void addFlag(ArrayNode flags, GoogleCampaignDto c, String type, String detail) {
    ObjectNode f = flags.addObject();
    f.put("campaignId", c.id());
    f.put("campaignName", c.name());
    f.put("type", type);
    f.put("detail", detail);
  }
}
