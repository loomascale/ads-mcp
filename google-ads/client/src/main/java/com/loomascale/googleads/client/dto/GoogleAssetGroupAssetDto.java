package com.loomascale.googleads.client.dto;

import java.util.List;

// One asset linked to a Performance Max asset group. `fieldType` is the slot it
// fills (HEADLINE, DESCRIPTION, MARKETING_IMAGE, YOUTUBE_VIDEO, ...), so counting
// field types is how "this group has no video" becomes visible.
//
// `primaryStatus` is whether the asset is actually eligible to serve, and
// `primaryStatusReasons` says why when it is not — the per-asset counterpart of the
// same pair on the asset group. Google's old LOW/GOOD/BEST performance label was
// removed from the API for Performance Max, so this is what per-asset diagnosis is
// now built on. `text` is set for text assets only — an image or video asset carries
// no text to show.
public record GoogleAssetGroupAssetDto(
    String assetGroupId,
    String assetId,
    String fieldType,
    String status,
    String primaryStatus,
    List<String> primaryStatusReasons,
    String text) {}
