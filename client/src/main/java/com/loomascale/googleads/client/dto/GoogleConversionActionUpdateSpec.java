package com.loomascale.googleads.client.dto;

// What a conversion action edit changes. Every field is nullable and null means "leave
// this setting alone": only the fields that are set reach the request body and the
// update mask, the same contract GoogleAdsApiClient.adCopyUpdateOperation uses for ad
// copy. An all-null spec would send an empty mask, which Google rejects, so callers
// refuse it before building one.
//
// `defaultValueCents` is in minor units of `defaultCurrencyCode` for symmetry with
// GoogleConversionActionDto — Google's value_settings.default_value is a plain
// major-unit amount, so the client divides on the way out.
//
// include_in_conversions_metric is deliberately absent: Google made it read-only on
// 2022-08-22 and every attempt to mutate it comes back as IMMUTABLE_FIELD. What a
// campaign counts is chosen through conversion goals instead
// (google_update_campaign_conversion_goals).
public record GoogleConversionActionUpdateSpec(
    String countingType,
    Boolean primaryForGoal,
    String status,
    Long defaultValueCents,
    String defaultCurrencyCode,
    Boolean alwaysUseDefaultValue,
    Integer clickThroughLookbackDays,
    Integer viewThroughLookbackDays,
    String category) {

  public boolean isEmpty() {
    return countingType == null
        && primaryForGoal == null
        && status == null
        && defaultValueCents == null
        && defaultCurrencyCode == null
        && alwaysUseDefaultValue == null
        && clickThroughLookbackDays == null
        && viewThroughLookbackDays == null
        && category == null;
  }
}
