package com.loomascale.googleads.client.dto;

import java.util.List;

// One Performance Max asset group with its performance over the range. An asset
// group is where PMax actually spends, so this is the level a PMax campaign can be
// audited at — the campaign row alone hides which creative set is carrying it.
//
// `adStrength` (POOR..EXCELLENT) is Google's read on whether the group has enough
// assets to compete, and `primaryStatusReasons` says why a group is not serving.
// Money normalized from micros to cents.
public record GoogleAssetGroupDto(
    String id,
    String name,
    String status,
    String primaryStatus,
    List<String> primaryStatusReasons,
    String adStrength,
    String campaignId,
    String campaignName,
    long impressions,
    long clicks,
    long costCents,
    double ctr,
    double conversions,
    long conversionsValueCents) {}
