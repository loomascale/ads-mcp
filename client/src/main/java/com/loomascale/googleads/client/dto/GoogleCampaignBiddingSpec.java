package com.loomascale.googleads.client.dto;

// What a campaign bidding update writes. A record rather than four positional parameters
// because the amounts are in three different units and two of them are Long: a fourth
// amount next to them was a transposition waiting to happen.
//
// Every amount is nullable, and null carries meaning: the client always masks the chosen
// strategy's own leaf, and a masked path absent from the body is cleared, so "not given"
// and "clear it" are the same wire shape.
//
// targetRoas is a RATIO, not money and not micros — MaximizeConversionValue.target_roas is
// a plain double where 4.0 means four units of conversion value for every unit of spend.
// It is the only amount in this client that is not in micros.
public record GoogleCampaignBiddingSpec(
    String strategy, Long cpcBidCeilingMicros, Long targetCpaMicros, Double targetRoas) {}
