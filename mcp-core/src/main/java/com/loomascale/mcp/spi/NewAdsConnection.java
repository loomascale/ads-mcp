package com.loomascale.mcp.spi;

import java.time.Instant;
import java.util.List;

// A connection as it exists the moment a platform's consent flow completes, before it has
// been stored.
//
// Tokens are PLAINTEXT here. Encryption is the store's business — the connect flow has
// just received these from the platform and should not have to know how, or whether, they
// are protected at rest. That is the whole point of the store being an interface.
public record NewAdsConnection(
    String userId,
    String platformKey,
    AdsConnectionState state,
    String accessToken,
    // Null when the platform issued none. Overwriting a stored one with null would destroy
    // the grant, so the store treats null as "keep what you have".
    String refreshToken,
    Instant expiresAt,
    // What the platform actually granted, which is not what was requested: users can
    // deselect individual permissions on a consent screen.
    String grantedScopes,
    List<AdsTarget> targets,
    String selectedTargetId,
    String defaultPageId) {}
