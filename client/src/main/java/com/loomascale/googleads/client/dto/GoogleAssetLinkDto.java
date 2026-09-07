package com.loomascale.googleads.client.dto;

import java.util.List;

// One asset linked to an asset group, carrying enough of the asset itself to diff on.
//
// The sibling GoogleAssetGroupAssetDto is a report row for google_list_asset_groups: text
// only, spend-ordered, and capped by clampLimit. This is an identity row for a write —
// every asset kind's payload, one asset group, uncapped — because an edit compares what the
// caller asked for against what is linked, and an image or video asset carries no text to
// compare. Widening the report DTO instead would ripple into that tool's pinned
// outputSchema for the sake of fields no reader wants.
//
// text is set for text assets, widthPixels/heightPixels/imageUrl for images,
// youtubeVideoId for videos and callToAction for a call-to-action asset; each is null for
// the kinds it does not apply to.
public record GoogleAssetLinkDto(
    String assetGroupId,
    String assetId,
    String assetResourceName,
    String fieldType,
    String linkStatus,
    String primaryStatus,
    List<String> primaryStatusReasons,
    String assetType,
    String assetName,
    String text,
    Integer widthPixels,
    Integer heightPixels,
    String imageUrl,
    String youtubeVideoId,
    String callToAction) {}
