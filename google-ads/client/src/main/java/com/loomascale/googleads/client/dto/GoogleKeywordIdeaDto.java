package com.loomascale.googleads.client.dto;

// One keyword suggestion from KeywordPlanIdeaService. `avgMonthlySearches` is
// Google's rounded 12-month average, `competition` the LOW/MEDIUM/HIGH bucket and
// `competitionIndex` the same thing as 0-100. The two bid figures are top-of-page
// bid estimates converted from micros to minor units. Everything except the
// keyword text is nullable — Google omits metrics for terms with too little data.
public record GoogleKeywordIdeaDto(
    String text,
    Long avgMonthlySearches,
    String competition,
    Integer competitionIndex,
    Long lowTopOfPageBidCents,
    Long highTopOfPageBidCents) {}
