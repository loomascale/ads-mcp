package com.loomascale.googleads.client.dto;

// One negative keyword criterion on a campaign — a query the campaign is told not to serve
// on, whatever its keywords or its Performance Max signals suggest. `criterionId` is the
// handle a removal needs, since a criterion's keyword text is immutable: changing a match
// type means removing this and adding a new one.
public record GoogleCampaignNegativeKeywordDto(
    String campaignId, String criterionId, String text, String matchType, String status) {}
