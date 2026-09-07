package com.loomascale.mcp.oauth;

import com.loomascale.mcp.spi.McpAlertSink;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

// The /oauth/token grants: authorization_code (with PKCE) and refresh_token (with
// rotation + reuse detection). All failures surface as OAuthTokenException carrying
// an RFC 6749 error code.
@Slf4j
@RequiredArgsConstructor
public class OAuthTokenGrantService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AuthorizationCodeStore codeStore;
  private final RefreshTokenStore refreshTokenStore;
  private final OAuthAccessTokenService accessTokenService;
  private final OAuthProperties properties;
  private final McpAlertSink alertSink;
  private final RefreshTokenFamilyRevoker familyRevoker;

  @Transactional
  public TokenResponse exchangeCode(
      String clientId, String code, String redirectUri, String codeVerifier) {
    if (code == null || code.isBlank() || codeVerifier == null || codeVerifier.isBlank()) {
      throw new OAuthTokenException("invalid_request", "Missing code or code_verifier");
    }
    String codeHash = PkceUtil.sha256Hex(code);
    OAuthAuthorizationCode entity =
        codeStore
            .findByCodeHash(codeHash)
            .orElseThrow(() -> new OAuthTokenException("invalid_grant", "Unknown code"));

    // Atomic single-use claim; false means it was already consumed, i.e. a replay.
    boolean claimed = codeStore.markUsed(codeHash, Instant.now());
    if (!claimed) {
      log.warn("Authorization code replay detected for client {}", entity.clientId());
      throw new OAuthTokenException("invalid_grant", "Code already used");
    }
    if (entity.expiresAt().isBefore(Instant.now())) {
      throw new OAuthTokenException("invalid_grant", "Code expired");
    }
    if (!entity.clientId().equals(clientId)) {
      throw new OAuthTokenException("invalid_grant", "client_id mismatch");
    }
    if (!entity.redirectUri().equals(redirectUri)) {
      throw new OAuthTokenException("invalid_grant", "redirect_uri mismatch");
    }
    if (!PkceUtil.verifyS256(codeVerifier, entity.codeChallenge())) {
      throw new OAuthTokenException("invalid_grant", "PKCE verification failed");
    }

    return issueTokens(entity.userId(), entity.clientId(), entity.scope(), null);
  }

  @Transactional
  public TokenResponse refresh(String clientId, String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new OAuthTokenException("invalid_request", "Missing refresh_token");
    }
    String tokenHash = PkceUtil.sha256Hex(refreshToken);
    OAuthRefreshToken stored =
        refreshTokenStore
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new OAuthTokenException("invalid_grant", "Unknown refresh_token"));

    if (stored.revoked()) {
      throw new OAuthTokenException("invalid_grant", "Refresh token revoked");
    }
    if (stored.rotated()) {
      // Reuse of an already-rotated token = theft signal. Kill the whole family in a
      // SEPARATE committed transaction so the revoke survives the invalid_grant throw
      // that rolls this method's transaction back.
      familyRevoker.revokeFamily(stored.familyId());
      alertSink.alert(
          "OAuth refresh-token reuse detected. Family revoked for user "
              + stored.userId()
              + " client "
              + stored.clientId());
      log.warn("Refresh token reuse — revoked family {}", stored.familyId());
      throw new OAuthTokenException("invalid_grant", "Refresh token reuse detected");
    }
    if (stored.expiresAt().isBefore(Instant.now())) {
      throw new OAuthTokenException("invalid_grant", "Refresh token expired");
    }
    if (!stored.clientId().equals(clientId)) {
      throw new OAuthTokenException("invalid_grant", "client_id mismatch");
    }

    refreshTokenStore.markRotated(stored.id());
    return issueTokens(
        stored.userId(), stored.clientId(), stored.scope(), stored.familyId());
  }

  private TokenResponse issueTokens(
      String userId, String clientId, String scope, String existingFamilyId) {
    String accessToken = accessTokenService.issue(userId, clientId, scope);
    String refreshTokenValue = randomToken();
    refreshTokenStore.save(
        new OAuthRefreshToken(
            UUID.randomUUID().toString(),
            PkceUtil.sha256Hex(refreshTokenValue),
            existingFamilyId != null ? existingFamilyId : UUID.randomUUID().toString(),
            clientId,
            userId,
            scope,
            Instant.now().plus(properties.getRefreshTokenTtlDays(), ChronoUnit.DAYS),
            false,
            false,
            Instant.now()));

    return new TokenResponse(
        accessToken,
        "Bearer",
        properties.getAccessTokenTtlSeconds(),
        refreshTokenValue,
        scope);
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public Optional<OAuthRefreshToken> findRefresh(String tokenHash) {
    return refreshTokenStore.findByTokenHash(tokenHash);
  }

  public record TokenResponse(
      String accessToken, String tokenType, long expiresIn, String refreshToken, String scope) {}

  public static class OAuthTokenException extends RuntimeException {
    private final String error;

    public OAuthTokenException(String error, String description) {
      super(description);
      this.error = error;
    }

    public String error() {
      return error;
    }
  }
}
