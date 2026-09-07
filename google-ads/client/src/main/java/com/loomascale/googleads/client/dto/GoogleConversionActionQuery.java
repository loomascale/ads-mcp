package com.loomascale.googleads.client.dto;

// Date range for the per-action conversion volume that goes alongside the
// conversion action configuration. Exactly one of datePreset (a GAQL DURING
// keyword) or (since, until) should be set. includeRemoved keeps deleted actions
// in the list — off by default, because a removed action explains history but not
// what bidding is being fed today.
public record GoogleConversionActionQuery(
    String datePreset, String since, String until, boolean includeRemoved) {}
