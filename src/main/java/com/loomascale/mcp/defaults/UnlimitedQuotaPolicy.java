package com.loomascale.mcp.defaults;

import com.loomascale.mcp.spi.QuotaPolicy;
import com.loomascale.mcp.spi.QuotaUsage;
import com.loomascale.mcp.tool.McpQuotaExceededException;
import com.loomascale.mcp.util.ExpiringCache;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

// The default policy: no monthly allowance, because a server talking to its operator's own
// ad accounts has nobody to bill.
//
// It is not a no-op, though. It keeps the runaway-loop guard, which is a safety control
// rather than a commercial one: a model that gets stuck can otherwise issue hundreds of
// write calls against a live ad account in a minute. The cap is per burst, not per month.
@Slf4j
public class UnlimitedQuotaPolicy implements QuotaPolicy {

  private final ExpiringCache<String, AtomicInteger> callsInWindow;
  private final int maxCallsPerWindow;

  public UnlimitedQuotaPolicy(Duration window, int maxCallsPerWindow) {
    this.callsInWindow = new ExpiringCache<>(window, 10_000);
    this.maxCallsPerWindow = maxCallsPerWindow;
  }

  @Override
  public void requireRemaining(String userId) {
    AtomicInteger count = callsInWindow.computeIfAbsent(userId, AtomicInteger::new);
    if (count.incrementAndGet() > maxCallsPerWindow) {
      log.warn("Runaway guard tripped for {}: over {} calls in one window", userId, maxCallsPerWindow);
      // Deliberately worded as a loop, not a quota: there is nothing to buy here, and
      // telling the model to upgrade would be a lie it would then repeat to the user.
      throw new McpQuotaExceededException(
          "Too many tool calls in a short period — this looks like a loop, so further calls"
              + " are being refused for a moment. Try again shortly, or make fewer calls per"
              + " request.");
    }
  }

  @Override
  public void record(String userId, String tool) {
    // Nothing to meter.
  }

  @Override
  public QuotaUsage usage(String userId) {
    return QuotaUsage.unlimited(0);
  }
}
