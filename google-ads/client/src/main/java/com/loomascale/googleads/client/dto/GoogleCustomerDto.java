package com.loomascale.googleads.client.dto;

// The account itself, as Google describes it. `status` is the one field that proves an
// account-level stop: SUSPENDED or CANCELED means nothing can serve no matter how
// healthy the campaigns look. `testAccount` accounts never serve real ads at all, which
// is worth saying out loud before anyone debugs missing impressions.
public record GoogleCustomerDto(
    String id,
    String name,
    String status,
    String currency,
    String timeZone,
    boolean testAccount,
    boolean manager) {}
