package com.loomascale.googleads.client.dto;

// Ad group row (Google's ad group ≈ Meta's ad set). Google ad groups have no own
// daily budget — spend is governed by the campaign budget. `cpcBidCents` is the ad
// group default bid in minor units; it is null when the account never set one and
// it has no effect on delivery while the campaign uses an automated bidding
// strategy.
public record GoogleAdGroupDto(
    String id, String name, String status, String type, String campaignId, Long cpcBidCents) {}
