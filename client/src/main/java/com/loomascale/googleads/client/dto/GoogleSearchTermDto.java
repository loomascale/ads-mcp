package com.loomascale.googleads.client.dto;

// One query a real user typed that triggered an ad, with what it cost. `status`
// says whether the term is already covered by a keyword (ADDED), already blocked
// (EXCLUDED), or neither (NONE / UNKNOWN) — the NONE rows are the actionable ones.
// `matchedKeywordText` is the keyword the query matched against, which is not the
// same string as the search term whenever the match type is broad or phrase.
// Money in minor units of the account currency.
public record GoogleSearchTermDto(
    String searchTerm,
    String status,
    String matchedKeywordText,
    String matchType,
    String adGroupId,
    String campaignId,
    long impressions,
    long clicks,
    long costCents,
    double ctr,
    double conversions) {}
