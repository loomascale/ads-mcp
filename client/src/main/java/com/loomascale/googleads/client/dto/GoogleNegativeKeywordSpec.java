package com.loomascale.googleads.client.dto;

// One campaign negative keyword as a tool receives it. No bid and no negative flag, unlike
// GoogleKeywordCreateSpec: a keyword criterion written on a campaign is always an exclusion,
// and Performance Max accepts keyword criteria only as exclusions in the first place.
public record GoogleNegativeKeywordSpec(String text, String matchType) {}
