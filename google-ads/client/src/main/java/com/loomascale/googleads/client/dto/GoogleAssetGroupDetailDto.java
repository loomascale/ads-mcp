package com.loomascale.googleads.client.dto;

import java.util.List;

// One Performance Max asset group as an editable object rather than a report row, plus the
// facts about its campaign that decide whether an edit is legal at all.
//
// GoogleAssetGroupDto cannot serve this purpose. That record carries metrics, so the query
// behind it segments by date, and a date-segmented GAQL report returns rows only for
// entities with data in the window — a brand-new or long-paused asset group is simply
// absent from it. Using it as an ownership guard would refuse to edit the group a create
// call had just made.
//
// campaignChannelType exists so a group on a non-PMax campaign is refused with our message
// instead of an opaque Google error; campaignStatus so an edit that enables the group can
// say whether that starts spend today; brandGuidelinesEnabled because when it is on Google
// moves BUSINESS_NAME, LOGO and LANDSCAPE_LOGO to the campaign (CampaignAsset), and linking
// them to the asset group fails with BRAND_ASSETS_NOT_LINKED_AT_CAMPAIGN_LEVEL.
public record GoogleAssetGroupDetailDto(
    String resourceName,
    String id,
    String name,
    String status,
    List<String> finalUrls,
    String path1,
    String path2,
    String primaryStatus,
    List<String> primaryStatusReasons,
    String adStrength,
    String campaignId,
    String campaignName,
    String campaignChannelType,
    String campaignStatus,
    boolean brandGuidelinesEnabled) {}
