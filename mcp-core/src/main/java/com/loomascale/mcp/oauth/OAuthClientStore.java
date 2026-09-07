package com.loomascale.mcp.oauth;

import java.util.Optional;

// Persistence for registered clients. An interface so a host can keep them wherever it
// already keeps such things; a JDBC implementation is provided and used by default.
public interface OAuthClientStore {

  Optional<OAuthClient> findByClientId(String clientId);

  void save(OAuthClient client);
}
