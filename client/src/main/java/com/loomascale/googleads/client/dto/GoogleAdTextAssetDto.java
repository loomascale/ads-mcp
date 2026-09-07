package com.loomascale.googleads.client.dto;

// One headline or description of a responsive search ad. `pinnedField` is set when the
// asset is pinned to a slot (HEADLINE_1, DESCRIPTION_1, …) — pinning stops Google
// rotating that slot, which is the usual reason an RSA underperforms its ad strength.
public record GoogleAdTextAssetDto(String text, String pinnedField) {}
