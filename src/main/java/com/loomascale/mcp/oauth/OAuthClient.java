package com.loomascale.mcp.oauth;

import java.time.Instant;
import java.util.List;

// A client registered through dynamic client registration (RFC 7591).
//
// A plain value rather than a JPA entity: this library must not contribute an
// @EntityScan to its host application, so persistence lives behind OAuthClientStore and
// the shape of the row is this record.
public record OAuthClient(
    String id,
    String clientId,
    String clientName,
    // Stored as one newline-separated string in the column; exposed here as a list so
    // callers never re-implement the split.
    List<String> redirectUris,
    String tokenEndpointAuthMethod,
    String scope,
    Instant createdAt) {

  public boolean allowsRedirectUri(String candidate) {
    return redirectUris.contains(candidate);
  }
}
