package com.loomascale.mcp.oauth;

import java.time.Instant;
import java.util.Optional;

// Persistence for authorization codes.
public interface AuthorizationCodeStore {

  Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash);

  void save(OAuthAuthorizationCode code);

  // Claims the code atomically and returns true only for the caller that won.
  //
  // This MUST be a conditional update rather than read-then-write: two concurrent
  // redemptions of the same code would otherwise both see usedAt == null and both mint a
  // token, which is precisely the replay that single-use codes exist to prevent.
  boolean markUsed(String codeHash, Instant now);
}
