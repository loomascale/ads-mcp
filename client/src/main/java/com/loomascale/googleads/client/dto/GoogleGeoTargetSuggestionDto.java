package com.loomascale.googleads.client.dto;

// A geo target constant Google suggested for a searched place name. `canonicalName`
// ("Kyiv,Kyiv city,Ukraine") is what disambiguates same-named places, and `targetType`
// ("City", "Region", "Country") says how wide the area is. `searchTerm` echoes the name
// that produced the suggestion, so a batch lookup stays attributable.
public record GoogleGeoTargetSuggestionDto(
    String searchTerm,
    String id,
    String name,
    String canonicalName,
    String countryCode,
    String targetType,
    String status) {}
