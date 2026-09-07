package com.loomascale.mcp.oauth;

import java.time.Instant;

// A refresh token, stored hashed. `familyId` ties every rotation of one original grant
// together: presenting an already-rotated token means the token leaked, and the whole
// family is revoked rather than just that one row.
public record OAuthRefreshToken(
    String id,
    String tokenHash,
    String familyId,
    String clientId,
    String userId,
    String scope,
    Instant expiresAt,
    boolean rotated,
    boolean revoked,
    Instant createdAt) {

  public boolean isUsable(Instant now) {
    return !revoked && !rotated && expiresAt != null && expiresAt.isAfter(now);
  }
}
