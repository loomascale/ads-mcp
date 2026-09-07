package com.loomascale.googleads.client.dto;

import java.util.List;

// A campaign's conversion goals plus where they come from. `goalConfigLevel` is
// CUSTOMER when the campaign simply inherits the account goals and CAMPAIGN once it
// carries its own — Google flips it to CAMPAIGN by itself the moment any campaign
// goal is updated. `goals` are always the campaign's own rows, which Google keeps
// materialized even while the campaign is on CUSTOMER level.
public record GoogleCampaignGoalsDto(
    String campaignId, String goalConfigLevel, List<GoogleConversionGoalDto> goals) {

  public static final String LEVEL_CUSTOMER = "CUSTOMER";
  public static final String LEVEL_CAMPAIGN = "CAMPAIGN";

  public boolean usesCampaignGoals() {
    return LEVEL_CAMPAIGN.equals(goalConfigLevel);
  }
}
