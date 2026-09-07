package com.loomascale.mcp.spi;

// A read-only view of one user's connection to an ad platform, carrying exactly what the
// ads tools and services need and nothing else.
//
// Deliberately a value, not an entity: no tokens (the store hands out a fresh one on
// request), no target snapshot (the store decrypts it), no persistence. That is what lets
// the same tool code run against a hosted, multi-tenant credential table and against a
// single-operator self-host reading credentials from the environment.
//
// Budget caps are in minor units of the account currency, matching how money is carried
// everywhere inside the server. Null means "no cap set" — fall back to the configured
// default rather than treating it as zero.
public record AdsConnection(
    String id,
    String userId,
    String platformKey,
    AdsConnectionState state,
    String grantedScopes,
    String selectedTargetId,
    Long maxDailyBudgetMinorUnits,
    Long maxAccountBudgetMinorUnits,
    // The page/profile an ad creative is published from, where the platform requires one.
    // Resolved by the store, which knows whether it was chosen explicitly or inherited
    // from another connection the same user holds.
    String defaultPageId) {}
