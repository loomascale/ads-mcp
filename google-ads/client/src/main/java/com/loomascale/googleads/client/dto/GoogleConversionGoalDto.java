package com.loomascale.googleads.client.dto;

// One conversion goal: a (category, origin) pair and whether bidding may optimize
// towards it. Goals are how Google decides what counts as a conversion — an
// individual conversion action only feeds Smart Bidding and the conversions column
// when the goal covering its category and origin is biddable.
//
// The same shape serves both levels: customer_conversion_goal, which every campaign
// inherits, and campaign_conversion_goal, the per-campaign override behind the
// "Use campaign-specific goal settings" toggle.
public record GoogleConversionGoalDto(String category, String origin, boolean biddable) {

  // The key Google builds its campaignConversionGoals resource name from, and the
  // form tools accept as CATEGORY:ORIGIN.
  public String key() {
    return category + ":" + origin;
  }

  // Whether the goal can be named in a resource name at all. Google materializes a row
  // for every enum value it knows, including the UNKNOWN sentinel it returns for a
  // category newer than the API version in use — and that row has no valid resource
  // name, so putting it in a mutate fails the whole request with BAD_RESOURCE_ID.
  public boolean addressable() {
    return isRealEnum(category) && isRealEnum(origin);
  }

  private static boolean isRealEnum(String value) {
    return value != null
        && !value.isBlank()
        && !"UNKNOWN".equals(value)
        && !"UNSPECIFIED".equals(value);
  }
}
