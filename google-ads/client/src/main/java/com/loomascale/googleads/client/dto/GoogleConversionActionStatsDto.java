package com.loomascale.googleads.client.dto;

// Volume for one conversion action over a date range. Comes from a separate query
// than the configuration, because conversion counts are segments of an account
// report rather than fields of the conversion action itself — `actionName` is the
// only key the two share.
//
// all_conversions is used rather than conversions on purpose: conversions counts
// only the actions marked primary, which is exactly the setting under audit.
public record GoogleConversionActionStatsDto(
    String actionName, String category, double allConversions, long allConversionsValueCents) {}
