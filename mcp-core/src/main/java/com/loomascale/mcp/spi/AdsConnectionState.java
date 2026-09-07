package com.loomascale.mcp.spi;

// Whether an ads connection can be used right now.
public enum AdsConnectionState {
  CONNECTED,
  // The grant is alive and refreshable, but the account owns no ad account to point it at.
  // Distinct from EXPIRED because repeating the consent cannot fix it — creating an ad
  // account can, and then the stored refresh token picks it up with no second sign-in.
  NO_AD_ACCOUNT,
  EXPIRED
}
