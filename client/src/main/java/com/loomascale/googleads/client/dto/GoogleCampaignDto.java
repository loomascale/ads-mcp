package com.loomascale.googleads.client.dto;

import java.util.List;

// Campaign row from a GAQL campaign query. Budgets normalized from micros to cents
// (minor units of the account currency). `budgetResourceName` is the CampaignBudget
// resource mutated by budget updates; `budgetExplicitlyShared` marks a budget used
// by several campaigns, which per-campaign updates must refuse to touch.
// `biddingStrategyType` decides whether manual bids matter at all: under
// MAXIMIZE_CLICKS and the other automated strategies Google ignores per-keyword and
// per-ad-group CPC bids, so the bid-setting tools warn instead of pretending.
// `cpcBidCeilingCents` is the Max CPC limit of a Maximize Clicks campaign
// (Campaign.target_spend.cpc_bid_ceiling_micros), null when unset or when the campaign
// runs another strategy — a limit set too low starves delivery, so it has to be
// readable. `biddingStrategyResourceName` is set only when a portfolio (shared)
// bidding strategy governs the campaign; its settings do not live on the campaign, so
// campaign-level bidding updates must refuse it the way shared budgets are refused.
// The geo target types come from Campaign.geo_target_type_setting: the positive one
// defaults to PRESENCE_OR_INTEREST, under which a campaign "targeting Kyiv" also shows
// to people merely interested in Kyiv from anywhere — the usual reason geography-scoped
// spend leaks, so it has to be readable. `primaryStatus` is Google's own eligibility
// verdict and `primaryStatusReasons` says why it is not serving — a campaign can be
// ENABLED and still NOT_ELIGIBLE, which is exactly the case plain status hides.
//
// `targetCpaCents` and `targetRoas` are the automated strategies' own targets, so a bidding
// edit can report the value it did not write instead of only the one it did. targetRoas is a
// RATIO — 4.0 is four units of conversion value per unit of spend — and is the one amount in
// this record that is not money.
//
// `startDateTime` and `endDateTime` are "yyyy-MM-dd HH:mm:ss" in the serving customer's own
// timezone — Google renamed these from the bare start_date/end_date in v24 and changed their
// type at the same time, so anything reading them has to take the date part rather than
// parse the whole value as a date. A campaign that runs until it is paused has no end date
// at all; GoogleAdsApiClient.endDateTimeOrNull normalizes that to null.
//
// `brandGuidelinesEnabled` is on by default for Performance Max campaigns created since
// v21, and when it is on Google holds the business name and logos on the CAMPAIGN rather
// than on each asset group — so it decides which tool can edit those three field types at
// all. Null for a channel where the flag does not apply.
public record GoogleCampaignDto(
    String id,
    String name,
    String status,
    String channelType,
    Long dailyBudgetCents,
    String budgetResourceName,
    boolean budgetExplicitlyShared,
    String biddingStrategyType,
    Long cpcBidCeilingCents,
    String biddingStrategyResourceName,
    String positiveGeoTargetType,
    String negativeGeoTargetType,
    String primaryStatus,
    List<String> primaryStatusReasons,
    Long targetCpaCents,
    Double targetRoas,
    String startDateTime,
    String endDateTime,
    Boolean brandGuidelinesEnabled) {

  // The scheduling date without the time of day, which is what a campaign schedule means to
  // an advertiser and what every reader here wants. Kept on the record so the two tools that
  // need it cannot disagree about how a date-time is trimmed.
  public String startDate() {
    return datePart(startDateTime);
  }

  public String endDate() {
    return datePart(endDateTime);
  }

  private static String datePart(String dateTime) {
    if (dateTime == null || dateTime.isBlank()) {
      return null;
    }
    int space = dateTime.indexOf(' ');
    return space < 0 ? dateTime : dateTime.substring(0, space);
  }
}
