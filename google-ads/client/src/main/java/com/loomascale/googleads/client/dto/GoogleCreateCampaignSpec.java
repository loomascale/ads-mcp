package com.loomascale.googleads.client.dto;

import java.util.List;

// Everything google_create_campaign needs to build a paused Search campaign:
// budget -> campaign -> ad group -> responsive search ad -> keywords. Headlines
// (3-15, each <=30 chars) and descriptions (2-4, each <=90 chars) follow Google's
// responsive-search-ad limits, validated by the tool before any mutate call.
// `locations` holds the raw location entries as given — a geo target constant id or a
// place name per entry, resolved against Google once the token is in hand. Empty means
// the campaign targets all locations, which is Google's default and rarely what anyone
// wants. `languages` works the same way — an id, an ISO code or a language name per
// entry — and empty likewise means every language.
public record GoogleCreateCampaignSpec(
    String name,
    long dailyBudgetCents,
    String finalUrl,
    List<String> headlines,
    List<String> descriptions,
    List<String> keywords,
    List<String> locations,
    List<String> languages) {}
