package com.loomascale.googleads.client.dto;

import java.util.List;

// One sitelink as it is actually attached: the asset's own payload (the text a searcher sees
// and the page a click lands on) plus the link that decides where it serves.
//
// `ownerId` and `ownerName` are the campaign or ad group holding the link, and both are null
// at account level, where the link belongs to the customer and there is nothing narrower to
// name.
//
// The metrics are null unless the caller asked for them, and absent is not zero — a sitelink
// that never served has no performance to report. They are split deliberately:
// `clicksOnSitelink` is clicks on this sitelink, `clicksOnAdWithSitelink` is clicks elsewhere
// in the ad while it was showing. Google reports the two in one unsegmented number, and
// calling that number "sitelink clicks" is the wrong answer this split exists to prevent.
public record GoogleSitelinkDto(
    GoogleSitelinkLevel level,
    String ownerId,
    String ownerName,
    String campaignId,
    String campaignName,
    String assetId,
    String assetResourceName,
    String linkText,
    String description1,
    String description2,
    List<String> finalUrls,
    List<String> finalMobileUrls,
    String linkStatus,
    String primaryStatus,
    String startDate,
    String endDate,
    Long clicksOnSitelink,
    Long clicksOnAdWithSitelink,
    Long impressions,
    Long costCents,
    Double conversions) {

  /** False when no metrics were requested, or Google reported none for the window. */
  public boolean hasMetrics() {
    return impressions != null;
  }
}
