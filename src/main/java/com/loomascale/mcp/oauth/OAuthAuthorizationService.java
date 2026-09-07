package com.loomascale.mcp.oauth;

import com.loomascale.mcp.consent.ConsentUrlResolver;
import com.loomascale.mcp.util.ExpiringCache;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// The /oauth/authorize half of the flow. Validates the request, parks it in a
// short-lived pending cache keyed by request_id, and sends the browser to the
// frontend consent page. On approve, mints a single-use code bound to
// client + redirect_uri + PKCE challenge.
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthAuthorizationService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final OAuthClientStore clientStore;
  private final AuthorizationCodeStore codeStore;
  private final DynamicClientRegistrationService registrationService;
  private final OAuthProperties properties;
  private final ConsentUrlResolver consentUrls;

  // request_id -> pending authorization, 30-min TTL (browser consent window; generous for app reviewers).
  private final ExpiringCache<String, PendingAuthorization> pending =
      new ExpiringCache<>(Duration.ofMinutes(30), 10_000);

  // Returns the consent-page URL to redirect the browser to. Throws
  // InvalidClientException for bad client/redirect (controller must 400, never
  // redirect); throws AuthorizationRedirectException for spec errors that DO
  // redirect back to the client with error=.
  public String beginAuthorization(
      String clientId,
      String redirectUri,
      String responseType,
      String scope,
      String state,
      String codeChallenge,
      String codeChallengeMethod,
      String resource,
      String lang) {

    OAuthClient client =
        clientStore
            .findByClientId(clientId == null ? "" : clientId)
            .orElseThrow(() -> new InvalidClientException("Unknown client_id"));

    List<String> registered = client.redirectUris();
    if (redirectUri == null || registered.stream().noneMatch(redirectUri::equals)) {
      // Exact match required; never redirect to an unvalidated URI.
      throw new InvalidClientException("redirect_uri does not match a registered value");
    }

    if (!"code".equals(responseType)) {
      throw new AuthorizationRedirectException(redirectUri, state, "unsupported_response_type");
    }
    if (codeChallenge == null || codeChallenge.isBlank()) {
      throw new AuthorizationRedirectException(redirectUri, state, "invalid_request");
    }
    if (!PkceUtil.METHOD_S256.equals(codeChallengeMethod)) {
      // Only S256; "plain" and missing method are rejected.
      throw new AuthorizationRedirectException(redirectUri, state, "invalid_request");
    }
    if (resource != null && !properties.resource().equals(resource)) {
      throw new AuthorizationRedirectException(redirectUri, state, "invalid_target");
    }

    String requestId = UUID.randomUUID().toString();
    pending.put(
        requestId,
        new PendingAuthorization(
            clientId,
            redirectUri,
            OAuthScopes.narrow(scope),
            state,
            codeChallenge,
            codeChallengeMethod,
            properties.resource(),
            lang));
    log.debug("Began authorization request {} for client {}", requestId, clientId);
    return consentUrls.consentUrl(requestId, lang);
  }

  public Optional<ConsentInfo> getPending(String requestId) {
    PendingAuthorization p = pending.get(requestId).orElse(null);
    if (p == null) {
      return Optional.empty();
    }
    String clientName =
        clientStore
            .findByClientId(p.clientId())
            .map(OAuthClient::clientName)
            .orElse(p.clientId());
    // Registration is open, so client_name alone proves nothing — show the user the
    // host the authorization code will actually be delivered to.
    return Optional.of(
        new ConsentInfo(clientName, List.of(p.scope().split(" ")), redirectHost(p.redirectUri())));
  }

  // User approved. Consume the pending request, mint a code, return the client
  // redirect with code+state (+iss per RFC 9207).
  public String approve(String requestId, String userId) {
    PendingAuthorization p = consume(requestId);
    String code = randomToken();
    // Only the hash is persisted: the code itself is returned to the client once and a
    // leaked database row must not be redeemable.
    codeStore.save(
        new OAuthAuthorizationCode(
            java.util.UUID.randomUUID().toString(),
            PkceUtil.sha256Hex(code),
            p.clientId(),
            userId,
            p.redirectUri(),
            p.scope(),
            p.codeChallenge(),
            p.codeChallengeMethod(),
            p.resource(),
            Instant.now().plusSeconds(properties.getCodeTtlSeconds()),
            null,
            Instant.now()));
    log.debug("Issued authorization code for user {} client {}", userId, p.clientId());

    StringBuilder url = new StringBuilder(p.redirectUri());
    url.append(p.redirectUri().contains("?") ? "&" : "?");
    url.append("code=").append(enc(code));
    if (p.state() != null) {
      url.append("&state=").append(enc(p.state()));
    }
    url.append("&iss=").append(enc(properties.getIssuer()));
    return url.toString();
  }

  public String deny(String requestId) {
    PendingAuthorization p = consume(requestId);
    StringBuilder url = new StringBuilder(p.redirectUri());
    url.append(p.redirectUri().contains("?") ? "&" : "?");
    url.append("error=access_denied");
    if (p.state() != null) {
      url.append("&state=").append(enc(p.state()));
    }
    return url.toString();
  }

  private PendingAuthorization consume(String requestId) {
    PendingAuthorization p = pending.get(requestId).orElse(null);
    if (p == null) {
      throw new InvalidClientException("Authorization request expired or unknown");
    }
    pending.invalidate(requestId);
    return p;
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // Host of a redirect_uri already validated at registration time; never blank in
  // practice, but fall back to the raw value rather than showing the user nothing.
  private String redirectHost(String redirectUri) {
    try {
      String host = URI.create(redirectUri).getHost();
      return host == null ? redirectUri : host;
    } catch (Exception e) {
      log.warn("Could not parse host from redirect_uri {}", redirectUri);
      return redirectUri;
    }
  }

  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record PendingAuthorization(
      String clientId,
      String redirectUri,
      String scope,
      String state,
      String codeChallenge,
      String codeChallengeMethod,
      String resource,
      String lang) {}

  public record ConsentInfo(String clientName, List<String> scopes, String redirectHost) {}

  // Bad client or redirect_uri — controller returns 400 and never redirects.
  public static class InvalidClientException extends RuntimeException {
    public InvalidClientException(String message) {
      super(message);
    }
  }

  // Spec error that redirects back to the (validated) client redirect_uri.
  public static class AuthorizationRedirectException extends RuntimeException {
    private final String redirectUri;
    private final String state;
    private final String error;

    public AuthorizationRedirectException(String redirectUri, String state, String error) {
      super(error);
      this.redirectUri = redirectUri;
      this.state = state;
      this.error = error;
    }

    public String redirectUrl() {
      StringBuilder url = new StringBuilder(redirectUri);
      url.append(redirectUri.contains("?") ? "&" : "?");
      url.append("error=").append(error);
      if (state != null) {
        url.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
      }
      return url.toString();
    }
  }
}
