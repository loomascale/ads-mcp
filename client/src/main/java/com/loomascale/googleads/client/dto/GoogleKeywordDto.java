package com.loomascale.googleads.client.dto;

import java.util.List;

// One positive or negative keyword criterion inside an ad group. The three status
// fields answer different questions and none of them replaces the others:
// `status` is what the advertiser set (ENABLED/PAUSED), `systemServingStatus` is
// whether Google will actually serve it (RARELY_SERVED means too little search
// volume), and `primaryStatus` plus `primaryStatusReasons` is Google's own
// explanation of why a keyword is or is not eligible. Money in minor units.
// Nullable boxes are fields Google omits for negative keywords or for accounts
// without enough data (quality score needs impressions first).
public record GoogleKeywordDto(
    String criterionId,
    String text,
    String matchType,
    String status,
    boolean negative,
    String systemServingStatus,
    String primaryStatus,
    List<String> primaryStatusReasons,
    Integer qualityScore,
    Long cpcBidCents,
    String adGroupId,
    String adGroupName,
    String campaignId) {}
