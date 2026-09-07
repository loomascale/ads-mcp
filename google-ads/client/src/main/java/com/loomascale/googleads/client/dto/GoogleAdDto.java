package com.loomascale.googleads.client.dto;

import java.util.List;

// Ad row from an ad_group_ad GAQL query. `approvalStatus` is the policy verdict
// (APPROVED, DISAPPROVED, ...); `status` is the configured on/off state. `finalUrls`
// are the landing pages a click actually goes to — the question a reader of an ad list
// asks first, and one nothing else in the account answers. `headlines`, `descriptions`
// and the two paths are the responsive-search-ad copy; they stay empty for other ad
// types, which carry their text in type-specific fields of their own.
public record GoogleAdDto(
    String id,
    String name,
    String type,
    String status,
    String approvalStatus,
    String adGroupId,
    String campaignId,
    List<String> finalUrls,
    List<GoogleAdTextAssetDto> headlines,
    List<GoogleAdTextAssetDto> descriptions,
    String path1,
    String path2) {}
