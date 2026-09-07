package com.loomascale.googleads.client.dto;

// One campaigns:mutate update of the plain leaf fields of a campaign. A null field is left
// alone — its path never enters the field mask — while clearEndDate masks end_date with no
// value in the body, which is how Google removes an end date rather than setting one.
//
// A record rather than three loose strings plus a boolean because "not given" and "clear it"
// are different requests that look identical as a null String.
public record GoogleCampaignSettingsUpdate(
    String name, String startDate, String endDate, boolean clearEndDate) {}
