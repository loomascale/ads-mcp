package com.loomascale.googleads.client.dto;

// One language criterion attached to a campaign — a row of the Languages setting in
// Google Ads. `languageId` is the language constant id ("1000"), `code` its ISO code
// ("en") and `name` its English name ("English"), both filled in from the language
// constant table. `criterionId` is what google_update_campaign_targeting needs to remove
// it. A campaign with no language criteria at all serves to every language, which is
// Google's default and the usual reason English copy shows to people browsing in another
// language.
public record GoogleLanguageCriterionDto(
    String campaignId,
    String criterionId,
    String languageId,
    String code,
    String name,
    String status) {}
