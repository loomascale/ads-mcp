package com.loomascale.googleads.client.dto;

// One keyword criterion to create inside an existing ad group. `negative` marks an
// excluded keyword, which Google refuses to accept a bid for — the tools validate
// that before this record is built. `cpcBidMicros` is null when the caller leaves
// bidding to the ad group default, `status` is ENABLED or PAUSED.
public record GoogleKeywordCreateSpec(
    String text, String matchType, boolean negative, Long cpcBidMicros, String status) {}
