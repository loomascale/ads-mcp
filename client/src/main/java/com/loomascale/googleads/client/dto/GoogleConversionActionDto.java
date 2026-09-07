package com.loomascale.googleads.client.dto;

// One conversion action's configuration — the settings that decide what bidding
// optimizes towards and what shows up in the conversions column.
//
// The audit-relevant fields: `primaryForGoal` true means this action feeds bidding
// and the main conversions metric; `countingType` ONE_PER_CLICK vs MANY_PER_CLICK
// decides whether repeat events inflate the number; `defaultValueCents` with
// `alwaysUseDefaultValue` means every conversion is worth the same flat amount
// regardless of the real order value, which quietly breaks value-based bidding.
// Money is in minor units of the currency named by `defaultCurrencyCode`, which
// may differ from the account currency.
public record GoogleConversionActionDto(
    String id,
    String name,
    String category,
    String type,
    String status,
    Boolean primaryForGoal,
    String countingType,
    String attributionModel,
    Long defaultValueCents,
    String defaultCurrencyCode,
    Boolean alwaysUseDefaultValue,
    Integer clickThroughLookbackDays,
    Integer viewThroughLookbackDays,
    Boolean includeInConversionsMetric,
    String origin) {}
