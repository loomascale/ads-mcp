package com.loomascale.mcp.money;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// Query-string encoding shared by every OAuth client. It lived on MetaOAuthClient, which
// made GoogleAdsOAuthClient depend on a Meta class for a helper that has nothing to do
// with Meta — and would make the Google ads server impossible to build without the Meta
// one once the two are separate artifacts.
public final class Urls {

  private Urls() {}

  // Null encodes as empty rather than the literal "null", so a missing optional parameter
  // drops out of the query string instead of being sent as a four-character value.
  public static String urlEncode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
