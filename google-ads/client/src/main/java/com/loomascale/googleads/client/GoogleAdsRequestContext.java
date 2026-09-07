package com.loomascale.googleads.client;

import com.fasterxml.jackson.databind.JsonNode;

// What a Google Ads HTTP call was about, carried alongside the request so a failure can
// be logged with the query or the operations that caused it. Google names the offending
// field but never echoes the request, and without this the log says which operation index
// was rejected while the operations themselves are gone.
//
// `customerId` and `loginCustomerId` are null for the one call made before any account is
// known, listAccessibleCustomers.
public record GoogleAdsRequestContext(String customerId, String loginCustomerId, JsonNode body) {

  public static GoogleAdsRequestContext of(
      String customerId, String loginCustomerId, JsonNode body) {
    return new GoogleAdsRequestContext(customerId, loginCustomerId, body);
  }

  public static GoogleAdsRequestContext ofAccount(String customerId, String loginCustomerId) {
    return new GoogleAdsRequestContext(customerId, loginCustomerId, null);
  }
}
