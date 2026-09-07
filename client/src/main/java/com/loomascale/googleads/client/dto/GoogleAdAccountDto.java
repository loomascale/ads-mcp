package com.loomascale.googleads.client.dto;

// A Google Ads customer account discovered from customer_client rows. `id` is the
// plain 10-digit customer id (no dashes). `loginCustomerId` is the directly
// accessible customer (usually an MCC) under which this account was found — it must
// be sent as the login-customer-id header on every API call that targets this
// account. `manager` accounts hold no campaigns and are not selectable targets.
public record GoogleAdAccountDto(
    String id,
    String name,
    String currency,
    String timeZone,
    boolean manager,
    String loginCustomerId) {}
