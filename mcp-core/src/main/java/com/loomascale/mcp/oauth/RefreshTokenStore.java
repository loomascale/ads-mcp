package com.loomascale.mcp.oauth;

import java.time.Instant;
import java.util.Optional;

// Persistence for refresh tokens, including family revocation on reuse.
public interface RefreshTokenStore {

  Optional<OAuthRefreshToken> findByTokenHash(String tokenHash);

  void save(OAuthRefreshToken token);

  void markRotated(String id);

  // Revokes every token descended from one original grant. Called when an
  // already-rotated token is presented, which means a copy leaked.
  int revokeFamily(String familyId);

  // Whether this user currently holds any live refresh token, i.e. whether an MCP client
  // is still connected on their behalf.
  boolean hasLiveToken(String userId, Instant now);
}
