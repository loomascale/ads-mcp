package com.loomascale.googleads.client.dto;

// One location criterion attached to a campaign — a row of the Locations tab in Google
// Ads. `geoTargetId` is the geo target constant id ("1012852"), `name` its canonical
// name ("Kyiv,Kyiv city,Ukraine") resolved in a second query, and `criterionId` is what
// google_update_campaign_targeting needs to remove it. `negative` true means the
// location is excluded rather than targeted; `bidModifier` is null when the criterion
// carries no bid adjustment.
public record GoogleLocationCriterionDto(
    String campaignId,
    String criterionId,
    String geoTargetId,
    String name,
    boolean negative,
    Double bidModifier,
    String status) {}
