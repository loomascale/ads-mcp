package com.loomascale.googleads.client.dto;

// One brand inside a BRANDS shared set.
//
// `entityId` is a Google Knowledge Graph machine id such as "/m/05p0rq" — it contains
// slashes, so it is never a sanitizeId() argument, never a lastSegment() input, and never
// interpolated into GAQL. It only ever travels inside a JSON request body.
//
// `sharedCriterionId` is what a removal addresses. displayName and primaryUrl are
// output-only: Google fills them in from the entity id.
public record GoogleBrandDto(
    String sharedCriterionId, String entityId, String displayName, String primaryUrl) {}
