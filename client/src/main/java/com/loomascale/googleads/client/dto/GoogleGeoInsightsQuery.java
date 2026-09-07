package com.loomascale.googleads.client.dto;

// Query params for the per-location performance report. campaignId narrows to one
// campaign. Exactly one of datePreset (a GAQL DURING keyword) or (since, until)
// should be set. limit caps rows, ordered by spend — a geo report has one row per
// location that served, which for a national campaign is hundreds.
public record GoogleGeoInsightsQuery(
    String campaignId, String datePreset, String since, String until, int limit) {}
