package com.loomascale.googleads.client.dto;

// Performance for one location. `locationId` is a Google geo target constant id,
// which is a number — `locationName` and `canonicalName` come from a second lookup
// and are null when that lookup could not name the id. `locationType` says whether
// the row is where the user physically was or the area they showed interest in,
// which changes what the numbers mean. Money normalized from micros to cents.
public record GoogleGeoRowDto(
    String locationId,
    String locationName,
    String canonicalName,
    String locationType,
    String campaignId,
    String campaignName,
    long impressions,
    long clicks,
    long costCents,
    double ctr,
    double conversions,
    long conversionsValueCents) {}
