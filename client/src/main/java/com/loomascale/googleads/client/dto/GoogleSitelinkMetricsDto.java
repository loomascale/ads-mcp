package com.loomascale.googleads.client.dto;

// Performance of one sitelink over a window, already split by what the click actually hit.
//
// Google reports a sitelink's row twice when the query is segmented by interaction target:
// once for clicks on the sitelink itself and once for clicks elsewhere in the ad while it was
// showing. Impressions are the same event in both rows, so they are taken, not summed.
public record GoogleSitelinkMetricsDto(
    long clicksOnSitelink,
    long clicksOnAdWithSitelink,
    long impressions,
    long costCents,
    double conversions) {

  public GoogleSitelinkMetricsDto merge(GoogleSitelinkMetricsDto other) {
    return new GoogleSitelinkMetricsDto(
        clicksOnSitelink + other.clicksOnSitelink,
        clicksOnAdWithSitelink + other.clicksOnAdWithSitelink,
        Math.max(impressions, other.impressions),
        costCents + other.costCents,
        conversions + other.conversions);
  }
}
