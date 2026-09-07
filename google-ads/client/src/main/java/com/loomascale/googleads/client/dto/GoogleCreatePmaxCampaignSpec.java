package com.loomascale.googleads.client.dto;

import java.util.List;
import java.util.Map;

// Everything google_create_pmax_campaign needs to build a paused Performance Max campaign:
// budget -> campaign -> asset group -> text assets -> image and video assets -> search themes.
// The text limits follow Google's asset group requirements and are validated by the tool
// before any mutate call: 3-15 headlines of 30 characters, 1-5 long headlines of 90, 2-5
// descriptions of 90 with at least one under 60, and a business name of 25.
//
// `imageUrlsByFieldType` is keyed by GoogleMediaSlot.fieldType() — MARKETING_IMAGE,
// SQUARE_MARKETING_IMAGE, PORTRAIT_MARKETING_IMAGE, LOGO, LANDSCAPE_LOGO — and holds the
// public https URLs the tool downloads and turns into assets. The slots Google requires a
// minimum of are always present because the tool refuses the call otherwise; an asset group
// that misses them cannot serve.
//
// `locations` and `languages` hold the raw entries as given — a geo target constant id or a
// place name per entry, an id, an ISO code or a language name per entry — resolved against
// Google once the token is in hand. Empty means the campaign targets every location and
// every language, which is Google's default and rarely what anyone wants.
//
// `targetRoas` is a bare ratio, not micros and not a percentage: 4.0 asks for four units of
// conversion value per unit of spend. Null means the campaign bids on conversion count with
// no target, which is the only safe strategy for a campaign with no history.
public record GoogleCreatePmaxCampaignSpec(
    String name,
    long dailyBudgetCents,
    String finalUrl,
    List<String> headlines,
    List<String> longHeadlines,
    List<String> descriptions,
    String businessName,
    Map<String, List<String>> imageUrlsByFieldType,
    List<String> youtubeVideoIds,
    List<String> searchThemes,
    List<String> locations,
    List<String> languages,
    Double targetRoas) {}
