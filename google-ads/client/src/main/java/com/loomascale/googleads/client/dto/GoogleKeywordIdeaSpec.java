package com.loomascale.googleads.client.dto;

import java.util.List;

// Seed for a keyword ideas request. At least one of `keywords` or `pageUrl` must
// be non-empty — Google rejects a seedless request, and which seed field the body
// uses depends on which of the two is present. `geoTargetIds` and `languageId` are
// raw Google constant ids (2840 = United States, 1000 = English); when omitted the
// API reports across all locations and languages.
public record GoogleKeywordIdeaSpec(
    List<String> keywords,
    String pageUrl,
    List<String> geoTargetIds,
    String languageId,
    int limit) {}
