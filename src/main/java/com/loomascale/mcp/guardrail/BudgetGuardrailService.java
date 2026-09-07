package com.loomascale.mcp.guardrail;

import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.tool.McpToolException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

// Server-side spend caps. NEVER trusts the model: create, update, and activation
// all route through enforce(). The cap is the per-connection value the user set
// in the dashboard, or the configured default when unset.
//
// Two caps, deliberately separate, because they measure different things: a daily
// budget is a rate (per campaign, per day) while a Google account budget is a
// total spending limit for the whole account. Mixing them would let a $100/day cap
// silently authorise or refuse a lifetime limit it says nothing about.
@Slf4j
public class BudgetGuardrailService {

  private final long defaultMaxDailyBudgetCents;
  private final long defaultMaxAccountBudgetCents;
  private final ProductBranding branding;

  public BudgetGuardrailService(
      @Value("${mcp.default-max-daily-budget-cents:10000}") long defaultMaxDailyBudgetCents,
      @Value("${mcp.default-max-account-budget-cents:100000}") long defaultMaxAccountBudgetCents,
      ProductBranding branding) {
    this.defaultMaxDailyBudgetCents = defaultMaxDailyBudgetCents;
    this.defaultMaxAccountBudgetCents = defaultMaxAccountBudgetCents;
    this.branding = branding;
  }

  public long capCents(AdsConnection connection) {
    Long cap = connection.maxDailyBudgetMinorUnits();
    return cap != null ? cap : defaultMaxDailyBudgetCents;
  }

  // Throws when requestedCents exceeds the cap. Message names the cap and where
  // to change it — never silently clamps. Both the request and the cap are in the
  // ad account's own currency, so the message must name that currency instead of
  // assuming dollars: a UAH account read "$100.00" for a 100 UAH cap.
  public void enforce(AdsConnection connection, long requestedCents, String currency) {
    if (requestedCents <= 0) {
      throw new McpToolException("Daily budget must be greater than zero.");
    }
    long cap = capCents(connection);
    if (requestedCents > cap) {
      throw new McpToolException(
          "Requested daily budget "
              + Money.display(requestedCents, currency)
              + " exceeds your cap of "
              + Money.display(cap, currency)
              + ". Raise the cap in the dashboard ("
              + branding.connectionsUrl()
              + ") if you intend to spend more.");
    }
    log.debug("Budget guardrail OK: requested {} within cap {}", requestedCents, cap);
  }

  public long accountCapCents(AdsConnection connection) {
    Long cap = connection.maxAccountBudgetMinorUnits();
    return cap != null ? cap : defaultMaxAccountBudgetCents;
  }

  // The account-level twin of enforce(). Same never-clamp contract, different
  // wording: the message has to say "total", or a refusal reads as the daily cap
  // and the user goes looking for the wrong setting.
  public void enforceAccountBudget(AdsConnection connection, long requestedCents, String currency) {
    if (requestedCents <= 0) {
      throw new McpToolException("Account budget must be greater than zero.");
    }
    long cap = accountCapCents(connection);
    if (requestedCents > cap) {
      throw new McpToolException(
          "Requested account budget "
              + Money.display(requestedCents, currency)
              + " exceeds your total account-budget cap of "
              + Money.display(cap, currency)
              + ". Raise the cap in the dashboard ("
              + branding.connectionsUrl()
              + ") if you intend to authorise more total spend.");
    }
    log.debug("Account budget guardrail OK: requested {} within cap {}", requestedCents, cap);
  }
}
