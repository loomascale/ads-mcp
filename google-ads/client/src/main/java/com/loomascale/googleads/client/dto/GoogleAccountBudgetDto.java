package com.loomascale.googleads.client.dto;

// An account budget — the spending limit sitting above every campaign budget, used by
// invoiced accounts. Money is in minor units of the account currency. A limit of type
// INFINITE has no amount; when a finite limit is reached, `amountServedCents` catches up
// with it and the whole account stops serving even though each campaign looks funded.
//
// The `proposed*` fields carry a change that has been submitted but not yet approved:
// account budgets are edited through proposals, so a raise can be visible here while
// the approved limit still holds. `adjustedSpendingLimitCents` is the effective limit
// (approved plus adjustments) and is what `amountServedCents` actually runs against.
public record GoogleAccountBudgetDto(
    String id,
    String name,
    String status,
    Long approvedSpendingLimitCents,
    String approvedSpendingLimitType,
    Long adjustedSpendingLimitCents,
    Long proposedSpendingLimitCents,
    String proposedSpendingLimitType,
    Long totalAdjustmentsCents,
    Long amountServedCents,
    String endDateTime) {}
