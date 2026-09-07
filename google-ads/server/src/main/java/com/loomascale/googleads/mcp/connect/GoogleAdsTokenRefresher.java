package com.loomascale.googleads.mcp.connect;

import com.loomascale.googleads.mcp.connect.GoogleAdsOAuthClient.GoogleToken;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.AdsTokenRefresher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Renews a Google access token. The only platform-specific part of refreshing — when to
// refresh, what to persist, and whether a failure is fatal all live in the connection
// store.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleAdsTokenRefresher implements AdsTokenRefresher {

  private final GoogleAdsOAuthClient oauthClient;

  @Override
  public String platformKey() {
    return AdsPlatforms.GOOGLE_ADS;
  }

  @Override
  public RefreshedTokens refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      // Google issues a refresh token only on the first consent, and only when
      // access_type=offline was requested. Without one there is nothing to renew, and the
      // user has to grant again — which is a revoked grant as far as the caller is
      // concerned.
      throw new GrantRevokedException("No refresh token is stored for this connection");
    }
    try {
      GoogleToken token = oauthClient.refreshAccessToken(refreshToken);
      return new RefreshedTokens(token.accessToken(), token.refreshToken(), token.expiresAt());
    } catch (RuntimeException e) {
      // invalid_grant is Google's answer for a refresh token that has been revoked,
      // expired through disuse, or belongs to a deleted account. It is the one case where
      // reconnecting is the only fix — everything else must be treated as transient, or a
      // brief outage at Google would disconnect every user permanently.
      if (isInvalidGrant(e)) {
        log.debug("Google refused the refresh token as invalid_grant");
        throw new GrantRevokedException("Google rejected the stored refresh token");
      }
      throw e;
    }
  }

  private boolean isInvalidGrant(RuntimeException e) {
    String message = e.getMessage();
    return message != null && message.contains("invalid_grant");
  }
}
