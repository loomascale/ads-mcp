package com.loomascale.googleads.client.dto;

// One asset linked to a campaign rather than to an asset group.
//
// This exists because of brand guidelines. Google enables them by default on Performance Max
// campaigns created since v21, and then BUSINESS_NAME, LOGO and LANDSCAPE_LOGO are held on the
// campaign as CampaignAssets instead of on each asset group — linking them to an asset group
// fails with BRAND_ASSETS_NOT_LINKED_AT_CAMPAIGN_LEVEL. So the same three field types live in
// two different places depending on one campaign flag, and this is the campaign-level half.
public record GoogleCampaignAssetDto(
    String campaignId,
    String assetId,
    String assetResourceName,
    String fieldType,
    String status,
    String text,
    Integer widthPixels,
    Integer heightPixels) {}
