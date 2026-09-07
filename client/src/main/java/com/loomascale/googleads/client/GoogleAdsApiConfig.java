package com.loomascale.googleads.client;

// The two things every Google Ads API request needs beyond a user's access token.
//
// A plain record rather than an injected configuration class, so this client stays usable
// without Spring — and so the dependency is honest: it needs exactly these two values, not
// a whole properties object.
public record GoogleAdsApiConfig(String apiVersion, String developerToken) {

  // Google's API is versioned in the URL path and old versions are switched off on a
  // published schedule, so this is a value a deployment must be able to change without
  // waiting for a release.
  public static final String DEFAULT_API_VERSION = "v21";

  public GoogleAdsApiConfig {
    if (apiVersion == null || apiVersion.isBlank()) {
      apiVersion = DEFAULT_API_VERSION;
    }
  }

  public String baseUrl() {
    return "https://googleads.googleapis.com/" + apiVersion;
  }
}
