package com.loomascale.googleads.client.dto;

import java.util.List;

// Both halves of a campaign's targeting, read in one campaign_criterion query: where it
// may show and in which languages. They travel together because every caller wants both
// and one query is one round trip instead of two.
public record GoogleTargetingCriteriaDto(
    List<GoogleLocationCriterionDto> locations, List<GoogleLanguageCriterionDto> languages) {}
