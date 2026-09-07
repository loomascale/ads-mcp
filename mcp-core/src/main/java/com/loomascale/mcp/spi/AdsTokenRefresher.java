package com.loomascale.mcp.spi;

import java.time.Instant;

// Renews an ad-platform access token. Contributed per platform, because the HTTP call is
// the only platform-specific part of refreshing: deciding WHEN to refresh, persisting the
// result, and deciding whether a failure means "expired" or "try again" are generic and
// belong to the connection store.
//
// The distinction the implementation must get right is which exception it throws.
// GrantRevokedException expires the connection and tells the user to reconnect. Anything
// else is treated as transient and leaves the connection intact — because a platform's
// token endpoint being briefly down must not disconnect every user permanently.
public interface AdsTokenRefresher {

  String platformKey();

  RefreshedTokens refresh(String refreshToken);

  // Tokens as returned by the platform. A null refreshToken means "keep the one you have":
  // most platforms only re-issue it sometimes, and overwriting it with null would destroy
  // the grant.
  record RefreshedTokens(String accessToken, String refreshToken, Instant expiresAt) {}

  // The grant itself is gone — the user revoked access, or the refresh token is no longer
  // accepted. Reconnecting is the only fix, so the connection is marked expired.
  class GrantRevokedException extends RuntimeException {
    public GrantRevokedException(String message) {
      super(message);
    }
  }
}
