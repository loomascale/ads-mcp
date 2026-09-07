package com.loomascale.googleads.client.dto;

import java.util.List;

// Query params for the sitelink report. `levels` is which of the three link levels to read —
// each one is its own query, because they are three different GAQL resources. campaignId and
// adGroupId narrow the campaign- and ad-group-level reads; both are ignored at account level,
// where a CustomerAsset belongs to the account rather than to anything inside it.
//
// Exactly one of datePreset or (since, until) should be set, and only when includeMetrics is
// true. Metrics add a second query per level rather than widening the first, because the
// per-sitelink numbers are only correct when segmented by interaction target and that segment
// multiplies every row.
public record GoogleSitelinkQuery(
    List<GoogleSitelinkLevel> levels,
    String campaignId,
    String adGroupId,
    String datePreset,
    String since,
    String until,
    int limit,
    boolean includeMetrics) {}
