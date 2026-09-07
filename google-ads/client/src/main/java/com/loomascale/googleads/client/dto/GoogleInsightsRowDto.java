package com.loomascale.googleads.client.dto;

import java.util.Map;

// One metrics row. `entityId`/`entityName` identify whatever the requested level
// aggregates by — a campaign, an ad group, or a keyword — and are null at account
// level. The campaign/adGroup fields carry the hierarchy above that entity so a
// keyword row is interpretable on its own; they are null at the levels that sit at
// or above them. `matchType` is set at keyword level only. Money normalized from
// micros to cents. `conversions` and `conversionsValueCents` come from the
// account's conversion tracking and are 0 when none is configured.
//
// `segments` holds the requested breakdown values for this row (device -> MOBILE)
// and is empty for an unsegmented query — with segments, entityId is no longer
// unique across rows and the segment values complete the key. The three impression
// share fields are null unless the query asked for them; they are fractions of 1,
// not percentages, and Google clamps them to >0.9 / <0.1 in low-data cases.
public record GoogleInsightsRowDto(
    String entityId,
    String entityName,
    String campaignId,
    String campaignName,
    String adGroupId,
    String adGroupName,
    String matchType,
    long impressions,
    long clicks,
    long costCents,
    double ctr,
    double avgCpcCents,
    double conversions,
    long conversionsValueCents,
    Map<String, String> segments,
    Double searchImpressionShare,
    Double searchBudgetLostImpressionShare,
    Double searchRankLostImpressionShare) {}
