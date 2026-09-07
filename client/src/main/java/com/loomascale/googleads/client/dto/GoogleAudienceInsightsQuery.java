package com.loomascale.googleads.client.dto;

// Query params for the per-audience performance report. campaignId and adGroupId
// narrow the report. Exactly one of datePreset (a GAQL DURING keyword) or
// (since, until) should be set. limit caps rows, ordered by spend.
public record GoogleAudienceInsightsQuery(
    String campaignId,
    String adGroupId,
    String datePreset,
    String since,
    String until,
    int limit) {}
