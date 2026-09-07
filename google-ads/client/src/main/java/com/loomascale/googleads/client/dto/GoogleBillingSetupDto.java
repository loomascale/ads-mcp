package com.loomascale.googleads.client.dto;

// A billing setup: the link between the account and a Google payments account. Only an
// APPROVED setup pays for ads — one still PENDING, or none at all, is an account that
// cannot spend. The payments account and profile ids are what a user needs to find the
// same record on the Billing page, since the API exposes no balance of its own.
public record GoogleBillingSetupDto(
    String id,
    String status,
    String paymentsAccountId,
    String paymentsAccountName,
    String paymentsProfileId,
    String paymentsProfileName,
    String startDateTime,
    String endDateTime) {}
