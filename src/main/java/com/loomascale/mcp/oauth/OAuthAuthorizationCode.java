package com.loomascale.mcp.oauth;

import java.time.Instant;

// An issued authorization code. Only the HASH of the code is stored: a leaked database
// row must not be redeemable, and the code is single-use, claimed by `usedAt`.
public record OAuthAuthorizationCode(
    String id,
    String codeHash,
    String clientId,
    String userId,
    String redirectUri,
    String scope,
    String codeChallenge,
    String codeChallengeMethod,
    // The protected resource the code was requested for (RFC 8707), so a code minted for
    // one resource cannot be exchanged for a token aimed at another.
    String resource,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt) {

  public boolean isExpired(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }
}
