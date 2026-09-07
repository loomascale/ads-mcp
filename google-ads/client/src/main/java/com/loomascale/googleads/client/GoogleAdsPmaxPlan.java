package com.loomascale.googleads.client;

import com.fasterxml.jackson.databind.node.ArrayNode;

// The operation list for one atomic Performance Max create, plus the two positions the
// caller needs to read out of the response.
//
// googleAds:mutate answers with one entry per operation in request order and no other way to
// tell which entry created what, so the builder that knows the order records where the
// campaign and the asset group landed rather than leaving the reader to recount. Package
// private: this is wire-building state inside the client, never a DTO for a controller.
record GoogleAdsPmaxPlan(
    ArrayNode operations, int campaignIndex, int assetGroupIndex, int assetLinkCount) {}
