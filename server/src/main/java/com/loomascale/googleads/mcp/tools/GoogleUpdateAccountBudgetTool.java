package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAccountBudgetDto;
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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Raises the account-level spending limit on an invoiced Google Ads account. That
// limit sits above every campaign budget: once it is exhausted the whole account
// stops serving, and no campaign-level change brings the ads back. google_check_billing
// already reports that blocker; this is the tool that clears it.
//
// Two deliberate refusals. It never sets an unlimited budget — removing the account's
// hard spend ceiling is the user's decision to make in Google Ads, not the agent's.
// And it never lowers a limit to at or below what has already been served, which
// would leave delivery stopped while reporting success.
@Component
@RequiredArgsConstructor
public class GoogleUpdateAccountBudgetTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_account_budget";
  }

  @Override
  public String description() {
    return "Raise the account-level spending limit of an invoiced Google Ads account, the total cap"
        + " above every campaign budget. Use when google_check_billing reports the account budget"
        + " is exhausted and nothing is serving. Cannot exceed your configured total account-budget"
        + " cap and never sets an unlimited budget. Accounts that pay by card have no account"
        + " budget, so this does not apply to them. For a campaign's daily budget, use"
        + " google_update_budget.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "spending_limit",
        "number",
        McpSchemas.moneyInputDescription("New total spending limit for the account budget."));
    McpSchemas.prop(
        schema,
        "account_budget_id",
        "string",
        "Account budget to change. Optional when the account has exactly one active budget.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "spending_limit");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "accountBudgetId", "string", "Account budget that was changed.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "requestedSpendingLimit", "Spending limit that was requested.");
    McpSchemas.prop(
        schema,
        "applied",
        "boolean",
        "True when the new limit is already in force. False when Google still has the change as a"
            + " pending proposal, which happens on some billing setups.");
    McpSchemas.nullableProp(
        schema,
        "effectiveSpendingLimit",
        "number",
        "Spending limit actually in force after the change, read back from Google.");
    McpSchemas.nullableProp(
        schema, "amountServed", "number", "Total already spent against this account budget.");
    McpSchemas.nullableProp(schema, "status", "string", "Account budget status.");
    McpSchemas.required(schema, "accountBudgetId", "requestedSpendingLimit", "applied");
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
    if (!args.hasNonNull("spending_limit")) {
      throw new McpToolException("spending_limit is required.");
    }
    long limitCents = Money.minorUnits(args.get("spending_limit").asDouble());
    Optional<String> requestedBudgetId =
        args.hasNonNull("account_budget_id")
            ? Optional.of(args.get("account_budget_id").asText())
            : Optional.empty();

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    // The cap and the request are both in the account currency, so resolve it
    // before the guardrail can refuse — the refusal message has to name it.
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);
    guardrail.enforceAccountBudget(connection, limitCents, currency);

    List<GoogleAccountBudgetDto> budgets =
        adsService.call(
            connection,
            () -> adsService.client().listAccountBudgets(token, customerId, loginCustomerId));
    GoogleAccountBudgetDto budget = selectBudget(budgets, requestedBudgetId, customerId);

    Long served = budget.amountServedCents();
    if (served != null && limitCents <= served) {
      throw new McpToolException(
          "Account "
              + customerId
              + " has already served "
              + Money.display(served, currency)
              + " against this budget, so a limit of "
              + Money.display(limitCents, currency)
              + " would leave it exhausted and the account still not serving. Choose a higher"
              + " limit.");
    }

    String resourceName = "customers/" + customerId + "/accountBudgets/" + budget.id();
    String argsSummary = "accountBudget=" + budget.id() + ";cents=" + limitCents;
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateAccountBudgetSpendingLimit(
                    token,
                    customerId,
                    loginCustomerId,
                    resourceName,
                    GoogleAdsApiClient.centsToMicros(limitCents));
            return null;
          });

      // A proposal is not necessarily live the moment it is accepted, so report what
      // Google says now rather than what was asked for.
      GoogleAccountBudgetDto after =
          adsService
              .call(
                  connection,
                  () -> adsService.client().listAccountBudgets(token, customerId, loginCustomerId))
              .stream()
              .filter(b -> budget.id() != null && budget.id().equals(b.id()))
              .findFirst()
              .orElse(budget);
      Long effective = effectiveLimitCents(after);
      boolean applied = effective != null && effective >= limitCents;

      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, budget.id(), true, null);
      audit.alert(
          "<b>MCP Ads: google_update_account_budget</b>\nUser: "
              + userId
              + "\nCustomer: "
              + customerId
              + "\nAccount budget: "
              + budget.id()
              + "\nNew total limit: "
              + Money.display(limitCents, currency)
              + "\nApplied: "
              + applied);

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("accountBudgetId", budget.id());
      structured.put("currency", currency);
      structured.put("requestedSpendingLimit", Money.majorUnits(limitCents));
      structured.put("applied", applied);
      if (effective != null) {
        structured.put("effectiveSpendingLimit", Money.majorUnits(effective));
      }
      if (after.amountServedCents() != null) {
        structured.put("amountServed", Money.majorUnits(after.amountServedCents()));
      }
      structured.put("status", after.status());
      return ToolResult.ok(
          text(applied, limitCents, currency, budget.id(), customerId), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, budget.id(), false, e.getMessage());
      throw e;
    }
  }

  // Account budgets are rare and few, but picking the wrong one changes the wrong
  // spend ceiling — so guess only when there is nothing to guess between.
  private GoogleAccountBudgetDto selectBudget(
      List<GoogleAccountBudgetDto> budgets, Optional<String> requestedId, String customerId) {
    if (budgets.isEmpty()) {
      throw new McpToolException(
          "Account "
              + customerId
              + " has no account-level budget. Only invoiced accounts have one; accounts that pay"
              + " by card are limited by their payment method and their campaign budgets instead."
              + " If ads are not serving, check the campaign budget, the bidding settings and the"
              + " account status with google_check_billing.");
    }
    if (requestedId.isPresent()) {
      return budgets.stream()
          .filter(b -> requestedId.get().equals(b.id()))
          .findFirst()
          .orElseThrow(
              () ->
                  new McpToolException(
                      "Account budget "
                          + requestedId.get()
                          + " was not found in account "
                          + customerId
                          + ". Use google_check_billing to list account budgets."));
    }
    if (budgets.size() > 1) {
      throw new McpToolException(
          "Account "
              + customerId
              + " has "
              + budgets.size()
              + " account budgets, so account_budget_id is required. Use google_check_billing to"
              + " list them.");
    }
    return budgets.get(0);
  }

  // The limit that spend actually runs against: approved plus any adjustments.
  private Long effectiveLimitCents(GoogleAccountBudgetDto budget) {
    return budget.adjustedSpendingLimitCents() != null
        ? budget.adjustedSpendingLimitCents()
        : budget.approvedSpendingLimitCents();
  }

  private String text(
      boolean applied, long limitCents, String currency, String budgetId, String customerId) {
    if (applied) {
      return "Raised the account budget of "
          + customerId
          + " to "
          + Money.display(limitCents, currency)
          + ". Delivery can resume once Google picks the change up.";
    }
    return "Submitted a change of account budget "
        + budgetId
        + " in account "
        + customerId
        + " to "
        + Money.display(limitCents, currency)
        + ". Google still reports it as pending rather than in force, which some billing setups"
        + " do; re-check with google_check_billing before assuming ads are serving again.";
  }
}
