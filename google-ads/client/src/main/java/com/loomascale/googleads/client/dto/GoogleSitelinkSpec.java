package com.loomascale.googleads.client.dto;

import java.util.List;

// One sitelink to create. `linkText` is the blue line a searcher clicks, the two descriptions
// are the optional lines under it, and `finalUrls` is where the click lands.
//
// Google requires the descriptions together or not at all, which the caller enforces before
// building one of these.
public record GoogleSitelinkSpec(
    String linkText,
    String description1,
    String description2,
    List<String> finalUrls,
    List<String> finalMobileUrls) {}
