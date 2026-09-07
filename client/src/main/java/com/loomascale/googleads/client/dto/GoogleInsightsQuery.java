package com.loomascale.googleads.client.dto;

import java.util.List;

// Query params for a Google Ads metrics GAQL query. Exactly one of datePreset
// (a GAQL DURING keyword such as LAST_7_DAYS) or (since, until) dates should be
// set; level is account|campaign|ad_group|keyword. campaignId and adGroupId
// optionally narrow the levels below account. limit caps rows at the ad group and
// keyword levels, where a real account produces far more rows than a tool result
// should carry; it is ignored at account and campaign level.
//
// segments splits every row by a reporting dimension (device, day_of_week, hour,
// ad_network_type) and multiplies the row count, so a segmented query is always
// row-capped whatever its level. includeImpressionShare adds the search
// impression-share metrics, which answer "is there headroom left" — they are not
// available at keyword level and are not combined with segments.
public record GoogleInsightsQuery(
    String level,
    String datePreset,
    String since,
    String until,
    String campaignId,
    String adGroupId,
    int limit,
    List<String> segments,
    boolean includeImpressionShare) {}
