package com.loomascale.googleads.client.dto;

// One signal attached to a Performance Max asset group — what the campaign is
// being told to chase. `kind` is SEARCH_THEME or AUDIENCE; `value` is the theme
// text, or the audience resource name for an audience signal, which is not always
// human-readable.
//
// `resourceName` is what a removal addresses. asset_group_signal supports create and
// remove but not update, so replacing a set of search themes means removing the signals
// that dropped out — and a signal's resource name is the only handle for that, which is
// why it travels back out of the read path and into the tool result.
public record GoogleAssetGroupSignalDto(
    String assetGroupId, String kind, String value, String resourceName) {}
