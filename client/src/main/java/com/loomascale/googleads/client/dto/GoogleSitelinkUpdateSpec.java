package com.loomascale.googleads.client.dto;

import java.util.List;

// What a sitelink edit changes. Every field is nullable and null means "leave this alone":
// only the fields that are set reach the request body and the update mask, the same contract
// GoogleConversionActionUpdateSpec uses. An all-null spec would send an empty mask, which
// Google rejects, so callers refuse it before building one.
//
// This edits the ASSET, not the link. A sitelink asset is account-scoped and can be linked to
// several campaigns, so an edit here lands on every one of them — which is why the tool reads
// back where the asset is linked before it mutates.
public record GoogleSitelinkUpdateSpec(
    String linkText,
    String description1,
    String description2,
    List<String> finalUrls,
    List<String> finalMobileUrls) {

  public boolean isEmpty() {
    return linkText == null
        && description1 == null
        && description2 == null
        && finalUrls == null
        && finalMobileUrls == null;
  }
}
