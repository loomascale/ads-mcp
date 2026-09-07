package com.loomascale.mcp.oauth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;

// Issues and validates MCP access tokens (JWT). Signed with oauth.access-token-secret,
// which is DISTINCT from jwt.secret so a leaked login token can never authorize an
// MCP call and vice versa. token_use=mcp_access + aud binding are belt-and-suspenders
// on top of the separate key.
@Slf4j
public class OAuthAccessTokenService {

  public static final String TOKEN_USE = "mcp_access";

  private final OAuthProperties properties;
  private final SecretKey key;

  public OAuthAccessTokenService(OAuthProperties properties) {
    this.properties = properties;
    if (!properties.isConfigured()) {
      // Fail loud but lazily — the MCP/OAuth endpoints reject requests when the
      // key is missing; the rest of the app still boots.
      log.warn("oauth.access-token-secret is not set — MCP access tokens cannot be issued");
      this.key = null;
    } else {
      this.key =
          Keys.hmacShaKeyFor(properties.getAccessTokenSecret().getBytes(StandardCharsets.UTF_8));
    }
  }

  public boolean isConfigured() {
    return key != null;
  }

  public String issue(String userId, String clientId, String scope) {
    requireKey();
    Date now = new Date();
    Date exp = new Date(now.getTime() + properties.getAccessTokenTtlSeconds() * 1000);
    return Jwts.builder()
        .issuer(properties.getIssuer())
        .subject(userId)
        .audience()
        .add(properties.resource())
        .and()
        .claim("token_use", TOKEN_USE)
        .claim("scope", scope == null ? "" : scope)
        .claim("client_id", clientId)
        .id(UUID.randomUUID().toString())
        .issuedAt(now)
        .expiration(exp)
        .signWith(key)
        .compact();
  }

  // Full validation: signature (own key), issuer, audience == resource, token_use,
  // expiry (enforced by the parser). Throws InvalidTokenException on any failure.
  public AccessTokenClaims validate(String bearerToken) {
    requireKey();
    try {
      Claims claims =
          Jwts.parser()
              .verifyWith(key)
              .requireIssuer(properties.getIssuer())
              .requireAudience(properties.resource())
              .build()
              .parseSignedClaims(bearerToken)
              .getPayload();
      if (!TOKEN_USE.equals(claims.get("token_use", String.class))) {
        throw new InvalidTokenException("Wrong token_use");
      }
      return new AccessTokenClaims(
          claims.getSubject(),
          claims.get("client_id", String.class),
          parseScopes(claims.get("scope", String.class)),
          claims.getId());
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException(e.getMessage());
    }
  }

  private Set<String> parseScopes(String scope) {
    Set<String> scopes = new LinkedHashSet<>();
    if (scope != null && !scope.isBlank()) {
      for (String s : scope.trim().split("\\s+")) {
        scopes.add(s);
      }
    }
    return scopes;
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException("oauth.access-token-secret is not configured");
    }
  }

  public record AccessTokenClaims(String userId, String clientId, Set<String> scopes, String jti) {}

  public static class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
      super(message);
    }
  }
}
