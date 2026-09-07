package com.loomascale.googleads.client.dto;

// Query params for a search term report. Exactly one of datePreset (a GAQL DURING
// keyword such as LAST_30_DAYS) or (since, until) should be set, matching
// GoogleInsightsQuery. campaignId/adGroupId optionally narrow the report, and
// limit caps the row count because a busy account produces thousands of terms.
public record GoogleSearchTermQuery(
    String datePreset,
    String since,
    String until,
    String campaignId,
    String adGroupId,
    int limit) {}
