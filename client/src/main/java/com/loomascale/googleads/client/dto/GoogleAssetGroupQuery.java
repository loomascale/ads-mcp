package com.loomascale.googleads.client.dto;

// Query params for the Performance Max asset group report. campaignId narrows to
// one PMax campaign, assetGroupId to a single asset group. Exactly one of
// datePreset (a GAQL DURING keyword) or (since, until) should be set. limit caps
// the asset group rows, ordered by spend.
//
// includeAssets and includeSearchThemes each add a further query — the assets in
// each group and the search theme / audience signals feeding it — so they stay
// opt-in rather than always-on.
public record GoogleAssetGroupQuery(
    String campaignId,
    String assetGroupId,
    String datePreset,
    String since,
    String until,
    int limit,
    boolean includeAssets,
    boolean includeSearchThemes) {}
