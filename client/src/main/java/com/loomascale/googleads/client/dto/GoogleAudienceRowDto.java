package com.loomascale.googleads.client.dto;

// Performance for one audience criterion attached to an ad group. `audienceType`
// is the criterion type (USER_LIST, USER_INTEREST, AUDIENCE, ...) and
// `audienceName` is the underlying resource name, which Google does not always
// return as human-readable text — a remarketing list arrives as a resource path,
// not a list name. Money normalized from micros to cents.
public record GoogleAudienceRowDto(
    String criterionId,
    String audienceType,
    String audienceName,
    String adGroupId,
    String adGroupName,
    String campaignId,
    String campaignName,
    long impressions,
    long clicks,
    long costCents,
    double ctr,
    double conversions,
    long conversionsValueCents) {}
