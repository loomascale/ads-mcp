package com.loomascale.googleads.client.dto;

import java.util.List;

// One negative brand_list criterion on a campaign: the shared set it points at, and the
// brands inside that set.
//
// `otherCampaignNames` is why the edit tool can refuse. A BRANDS shared set is a shared
// object by design and may be attached to several campaigns, so adding or removing a brand
// in it changes all of them — the same hazard as a shared campaign budget, and handled the
// same way.
public record GoogleBrandExclusionDto(
    String campaignId,
    String criterionId,
    String sharedSetId,
    String sharedSetName,
    List<GoogleBrandDto> brands,
    List<String> otherCampaignNames) {}
