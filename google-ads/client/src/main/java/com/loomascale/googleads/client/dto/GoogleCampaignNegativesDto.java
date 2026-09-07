package com.loomascale.googleads.client.dto;

import java.util.List;

// Everything a campaign is told NOT to serve on: plain negative keywords, and — for search
// and Performance Max campaigns — brand exclusions, which Google matches against every
// variation and misspelling of a brand in a way a negative keyword cannot.
public record GoogleCampaignNegativesDto(
    List<GoogleCampaignNegativeKeywordDto> keywords,
    List<GoogleBrandExclusionDto> brandExclusions) {}
