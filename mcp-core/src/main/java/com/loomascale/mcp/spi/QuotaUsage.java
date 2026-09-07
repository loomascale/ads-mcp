package com.loomascale.mcp.spi;

import java.time.Instant;

// A snapshot of one user's allowance. `limit` is null when the plan is unlimited, in
// which case `remaining` is null too — callers must render "unlimited" rather than a
// number, so an absent limit is modelled as absent rather than as a sentinel.
public record QuotaUsage(
    long used, Long limit, Long remaining, Instant resetsAt, boolean unlimited) {

  public static QuotaUsage unlimited(long used) {
    return new QuotaUsage(used, null, null, null, true);
  }

  public static QuotaUsage limited(long used, long limit, Instant resetsAt) {
    return new QuotaUsage(used, limit, Math.max(0, limit - used), resetsAt, false);
  }
}
