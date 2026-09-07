package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAccountBudgetDto;
import com.loomascale.googleads.client.dto.GoogleBillingSetupDto;
import com.loomascale.googleads.client.dto.GoogleCustomerDto;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Account-level reasons a Google Ads account cannot spend: its own status, whether a
// billing setup is approved, and whether an account budget has run out. This is the half
// of "why is nothing serving" that lives above the campaigns — google_check_pacing
// answers the other half, today's delivery.
//
// The Google Ads API has no card balance, no billing-hold flag and none of the
// payment-failure banners the Google Ads UI shows, so this tool reports what the API can
// actually prove and says plainly where it stops. Claiming "billing is fine" from an
// empty blocker list would be inventing a verdict.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCheckBillingTool implements AdsTool {

  private static final String APPROVED = "APPROVED";
  private static final String ENABLED = "ENABLED";
  private static final String BILLING_PAGE = "https://ads.google.com/aw/billing/summary";

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_check_billing";
  }

  @Override
  public String description() {
    return "Check whether a Google Ads account is able to spend at all: its account status, whether"
        + " a billing setup is approved and which payments account and profile it points at, and"
        + " whether an account budget is pending, cancelled or exhausted. Use this when campaigns"
        + " look enabled and ads look approved but nothing serves. The Google Ads API exposes no"
        + " card balance, no billing hold and none of the payment-failure banners the Google Ads UI"
        + " shows, so an empty blocker list means only that nothing billing-shaped is visible to"
        + " the API — the result says where to look next. For today's spend and delivery use"
        + " google_check_pacing; for per-campaign eligibility use google_list_campaigns.";
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
    McpSchemas.nullableProp(schema, "accountName", "string", "Account name in Google Ads.");
    McpSchemas.nullableProp(
        schema,
        "accountStatus",
        "string",
        "ENABLED, SUSPENDED, CANCELED or CLOSED. Anything but ENABLED stops every campaign in the"
            + " account.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.nullableProp(schema, "timeZone", "string", "Account time zone.");
    McpSchemas.prop(
        schema,
        "testAccount",
        "boolean",
        "True for a test account, which never serves real ads however healthy it looks.");
    McpSchemas.prop(schema, "manager", "boolean", "True for a manager (MCC) account.");

    ObjectNode setup =
        McpSchemas.objectArrayProp(
            schema,
            "billingSetups",
            "Billing setups on the account. Only an APPROVED one pays for ads.");
    McpSchemas.nullableProp(setup, "id", "string", "Billing setup id.");
    McpSchemas.nullableProp(setup, "status", "string", "APPROVED, PENDING or CANCELLED.");
    McpSchemas.nullableProp(
        setup, "paymentsAccountId", "string", "Google payments account the ads are billed to.");
    McpSchemas.nullableProp(setup, "paymentsAccountName", "string", "Payments account name.");
    McpSchemas.nullableProp(setup, "paymentsProfileId", "string", "Google payments profile id.");
    McpSchemas.nullableProp(setup, "paymentsProfileName", "string", "Payments profile name.");
    McpSchemas.nullableProp(setup, "startDateTime", "string", "When the setup starts paying.");
    McpSchemas.nullableProp(
        setup, "endDateTime", "string", "When it stops, null when it does not.");

    ObjectNode budget =
        McpSchemas.objectArrayProp(
            schema,
            "accountBudgets",
            "Account budgets, the spending limit above every campaign budget. Empty on accounts"
                + " that pay by card, which have none.");
    McpSchemas.nullableProp(budget, "id", "string", "Account budget id.");
    McpSchemas.nullableProp(budget, "name", "string", "Account budget name.");
    McpSchemas.nullableProp(budget, "status", "string", "APPROVED, PENDING or CANCELLED.");
    McpSchemas.moneyProp(
        budget, "spendingLimit", "Approved spending limit, null when it is unlimited.");
    McpSchemas.nullableProp(
        budget, "spendingLimitType", "string", "INFINITE when the budget has no limit.");
    McpSchemas.moneyProp(budget, "amountServed", "How much of the budget has been spent.");
    McpSchemas.nullableProp(budget, "endDateTime", "string", "When the budget ends.");

    McpSchemas.stringArrayProp(
        schema,
        "blockers",
        "Account-level reasons the API can prove that nothing can spend. Empty when it can prove"
            + " none — which is not the same as billing being healthy.");
    McpSchemas.stringArrayProp(
        schema,
        "unavailable",
        "Sections the connected user's permissions refused, with Google's own message. Billing data"
            + " needs billing access on the account.");
    McpSchemas.prop(
        schema,
        "note",
        "string",
        "What this check does and does not prove, and where to look next.");
    McpSchemas.required(
        schema,
        "customerId",
        "testAccount",
        "manager",
        "billingSetups",
        "accountBudgets",
        "blockers",
        "unavailable",
        "note");
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

    // The account row is not optional: without it there is nothing to report on.
    GoogleCustomerDto customer =
        adsService.call(
            connection,
            () -> adsService.client().customerDetails(token, customerId, loginCustomerId));
    if (customer == null) {
      throw new McpToolException("Google returned no account row for customer " + customerId + ".");
    }

    List<String> unavailable = new ArrayList<>();
    List<GoogleBillingSetupDto> setups =
        section(
            "billing setups",
            unavailable,
            () ->
                adsService.call(
                    connection,
                    () ->
                        adsService.client().listBillingSetups(token, customerId, loginCustomerId)));
    List<GoogleAccountBudgetDto> budgets =
        section(
            "account budgets",
            unavailable,
            () ->
                adsService.call(
                    connection,
                    () ->
                        adsService
                            .client()
                            .listAccountBudgets(token, customerId, loginCustomerId)));

    List<String> blockers = blockers(customer, setups, budgets, unavailable);
    log.debug(
        "google_check_billing user={} customer={} status={} setups={} budgets={} blockers={}",
        userId,
        customerId,
        customer.status(),
        setups.size(),
        budgets.size(),
        blockers.size());

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("accountName", customer.name());
    structured.put("accountStatus", customer.status());
    structured.put("currency", customer.currency());
    structured.put("timeZone", customer.timeZone());
    structured.put("testAccount", customer.testAccount());
    structured.put("manager", customer.manager());
    writeSetups(structured.putArray("billingSetups"), setups);
    writeBudgets(structured.putArray("accountBudgets"), budgets, customer.currency());
    ArrayNode blockerArr = structured.putArray("blockers");
    blockers.forEach(blockerArr::add);
    ArrayNode unavailableArr = structured.putArray("unavailable");
    unavailable.forEach(unavailableArr::add);
    String note = note(blockers.isEmpty());
    structured.put("note", note);

    return ToolResult.ok(text(customer, setups, budgets, blockers, unavailable, note), structured);
  }

  // Billing data is refused for users without billing access on the account. One refused
  // section must not sink the rest of the answer, so it is recorded and the check goes on.
  private <T> List<T> section(
      String name, List<String> unavailable, java.util.function.Supplier<List<T>> query) {
    try {
      return query.get();
    } catch (McpToolException e) {
      log.debug("google_check_billing could not read {}: {}", name, e.getMessage());
      unavailable.add(name + ": " + e.getMessage());
      return List.of();
    }
  }

  private List<String> blockers(
      GoogleCustomerDto customer,
      List<GoogleBillingSetupDto> setups,
      List<GoogleAccountBudgetDto> budgets,
      List<String> unavailable) {
    List<String> blockers = new ArrayList<>();
    if (customer.status() != null && !ENABLED.equals(customer.status())) {
      blockers.add(
          "The account status is "
              + customer.status()
              + ", so no campaign in it can serve, whatever its own status says.");
    }
    if (customer.testAccount()) {
      blockers.add(
          "This is a test account. Test accounts never serve real ads and never spend money.");
    }
    // Only judge billing setups when they were actually readable.
    boolean setupsReadable =
        unavailable.stream().noneMatch(entry -> entry.startsWith("billing setups"));
    if (setupsReadable) {
      if (setups.isEmpty()) {
        blockers.add(
            "The account has no billing setup at all, so there is no payments account to charge."
                + " Add one on the Billing page: "
                + BILLING_PAGE);
      } else if (setups.stream().noneMatch(setup -> APPROVED.equals(setup.status()))) {
        blockers.add(
            "No billing setup is APPROVED — the account has "
                + String.join(", ", setups.stream().map(GoogleBillingSetupDto::status).toList())
                + ". Until one is approved the account cannot be charged and will not serve.");
      }
    }
    for (GoogleAccountBudgetDto budget : budgets) {
      if (budget.status() != null && !APPROVED.equals(budget.status())) {
        blockers.add(
            "Account budget \"" + budget.name() + "\" is " + budget.status() + ", not APPROVED.");
      }
      Long limit =
          budget.adjustedSpendingLimitCents() != null
              ? budget.adjustedSpendingLimitCents()
              : budget.approvedSpendingLimitCents();
      if (limit != null
          && budget.amountServedCents() != null
          && budget.amountServedCents() >= limit) {
        blockers.add(
            "Account budget \""
                + budget.name()
                + "\" has spent its whole limit ("
                + Money.display(budget.amountServedCents(), customer.currency())
                + " of "
                + Money.display(limit, customer.currency())
                + "), which stops the whole account.");
      }
    }
    return blockers;
  }

  private String note(boolean noBlockers) {
    String limits =
        "The Google Ads API exposes no card balance, no billing hold and none of the payment-failure"
            + " banners the Google Ads UI shows, so this check covers account status, billing setup"
            + " and account budgets only.";
    if (!noBlockers) {
      return limits;
    }
    return limits
        + " Nothing billing-shaped is visible here, which is not proof that billing is healthy:"
        + " a declined card or a payment on hold would look exactly like this. Check the Billing"
        + " page directly: "
        + BILLING_PAGE;
  }

  private void writeSetups(ArrayNode arr, List<GoogleBillingSetupDto> setups) {
    for (GoogleBillingSetupDto setup : setups) {
      ObjectNode node = arr.addObject();
      node.put("id", setup.id());
      node.put("status", setup.status());
      node.put("paymentsAccountId", setup.paymentsAccountId());
      node.put("paymentsAccountName", setup.paymentsAccountName());
      node.put("paymentsProfileId", setup.paymentsProfileId());
      node.put("paymentsProfileName", setup.paymentsProfileName());
      node.put("startDateTime", setup.startDateTime());
      node.put("endDateTime", setup.endDateTime());
    }
  }

  private void writeBudgets(ArrayNode arr, List<GoogleAccountBudgetDto> budgets, String currency) {
    for (GoogleAccountBudgetDto budget : budgets) {
      ObjectNode node = arr.addObject();
      node.put("id", budget.id());
      node.put("name", budget.name());
      node.put("status", budget.status());
      if (budget.approvedSpendingLimitCents() != null) {
        node.put("spendingLimit", Money.majorUnits(budget.approvedSpendingLimitCents()));
      } else {
        node.putNull("spendingLimit");
      }
      node.put("spendingLimitType", budget.approvedSpendingLimitType());
      if (budget.amountServedCents() != null) {
        node.put("amountServed", Money.majorUnits(budget.amountServedCents()));
      } else {
        node.putNull("amountServed");
      }
      node.put("endDateTime", budget.endDateTime());
    }
  }

  private String text(
      GoogleCustomerDto customer,
      List<GoogleBillingSetupDto> setups,
      List<GoogleAccountBudgetDto> budgets,
      List<String> blockers,
      List<String> unavailable,
      String note) {
    StringBuilder text =
        new StringBuilder(
            "Google Ads account "
                + customer.id()
                + (customer.name() == null ? "" : " (" + customer.name() + ")")
                + " — status "
                + customer.status()
                + (customer.testAccount() ? ", TEST ACCOUNT" : "")
                + ".\n");
    text.append("Billing setups: ");
    if (setups.isEmpty()) {
      text.append("none.\n");
    } else {
      text.append("\n");
      for (GoogleBillingSetupDto setup : setups) {
        text.append("• ")
            .append(setup.status())
            .append(" — payments account ")
            .append(setup.paymentsAccountId())
            .append(
                setup.paymentsAccountName() == null ? "" : " (" + setup.paymentsAccountName() + ")")
            .append(", profile ")
            .append(setup.paymentsProfileId())
            .append("\n");
      }
    }
    text.append("Account budgets: ");
    if (budgets.isEmpty()) {
      text.append("none (normal for accounts paying by card).\n");
    } else {
      text.append("\n");
      for (GoogleAccountBudgetDto budget : budgets) {
        text.append("• ")
            .append(budget.name())
            .append(" [")
            .append(budget.status())
            .append("] spent ")
            .append(
                budget.amountServedCents() == null
                    ? "unknown"
                    : Money.display(budget.amountServedCents(), customer.currency()))
            .append(
                budget.approvedSpendingLimitCents() == null
                    ? " of an unlimited budget"
                    : " of "
                        + Money.display(budget.approvedSpendingLimitCents(), customer.currency()))
            .append("\n");
      }
    }
    if (blockers.isEmpty()) {
      text.append("No account-level blocker found.\n");
    } else {
      text.append("Blockers:\n");
      blockers.forEach(blocker -> text.append("• ").append(blocker).append("\n"));
    }
    if (!unavailable.isEmpty()) {
      text.append("Could not read:\n");
      unavailable.forEach(entry -> text.append("• ").append(entry).append("\n"));
    }
    return text.append(note).toString();
  }
}
