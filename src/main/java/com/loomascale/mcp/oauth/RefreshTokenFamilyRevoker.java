package com.loomascale.mcp.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Revokes a refresh-token family in its OWN transaction. Split into a separate
// bean so REQUIRES_NEW is honored via the Spring proxy: the reuse-detection path
// must commit the revoke even though the caller then throws invalid_grant (which
// rolls the caller's transaction back).
@RequiredArgsConstructor
public class RefreshTokenFamilyRevoker {

  private final RefreshTokenStore refreshTokenStore;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revokeFamily(String familyId) {
    refreshTokenStore.revokeFamily(familyId);
  }
}
