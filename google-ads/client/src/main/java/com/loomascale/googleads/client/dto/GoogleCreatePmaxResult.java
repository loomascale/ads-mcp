package com.loomascale.googleads.client.dto;

// What one atomic googleAds:mutate that built a Performance Max campaign created. The bulk
// endpoint answers with a resource name per operation in request order, so the two names
// worth keeping are read off their known positions and handed up as a record rather than as
// the raw response.
//
// `assetLinkCount` counts the asset group and campaign asset links the request created, which
// is what the tool reports back so the reader can tell a complete asset group from a thin one.
public record GoogleCreatePmaxResult(
    String campaignResourceName, String assetGroupResourceName, int assetLinkCount) {}
