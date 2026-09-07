package com.loomascale.googleads.mcp.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.googleads.client.GoogleAdsApiException;
import com.loomascale.mcp.money.Urls;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Google OAuth mechanics for the GOOGLE_ADS connection: authorization-code flow
// with offline access. Unlike Meta's long-lived tokens, Google access tokens live
// ~1 hour and are renewed with the refresh token obtained here (prompt=consent
// guarantees one on every connect). Mirrors MetaOAuthClient in shape.
@Slf4j
@Component
public class GoogleAdsOAuthClient {

  private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
  private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
  private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

  // ConnectionExpirySweeper hits this endpoint on a schedule; an unbounded client
  // would let one hung Google connection pin a scheduler thread indefinitely.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  private final ObjectMapper objectMapper;
  private final GoogleOAuthConfig config;

  public GoogleAdsOAuthClient(ObjectMapper objectMapper, GoogleOAuthConfig config) {
    this.objectMapper = objectMapper;
    this.config = config;
  }

  // access_type=offline + prompt=consent: Google only returns a refresh token on a
  // consenting grant, and a reconnect without prompt=consent would silently come
  // back with an access token only.
  public String buildAuthorizationUrl(String redirectUri, String state) {
    return AUTH_URL
        + "?client_id="
        + Urls.urlEncode(config.clientId())
        + "&redirect_uri="
        + Urls.urlEncode(redirectUri)
        + "&response_type=code"
        + "&scope="
        + Urls.urlEncode(config.scopesForRequest())
        + "&access_type=offline"
        + "&prompt=consent"
        + "&state="
        + Urls.urlEncode(state);
  }

  public GoogleToken exchangeCode(String code, String redirectUri) {
    String body =
        "grant_type=authorization_code"
            + "&code="
            + Urls.urlEncode(code)
            + "&client_id="
            + Urls.urlEncode(config.clientId())
            + "&client_secret="
            + Urls.urlEncode(config.clientSecret())
            + "&redirect_uri="
            + Urls.urlEncode(redirectUri);
    JsonNode node = postForm(TOKEN_URL, body, "Google Ads code exchange");
    return toToken(node, node.path("refresh_token").asText(null));
  }

  // Refresh responses normally omit refresh_token — the existing one stays valid,
  // so it is carried over into the returned record.
  public GoogleToken refreshAccessToken(String refreshToken) {
    String body =
        "grant_type=refresh_token"
            + "&refresh_token="
            + Urls.urlEncode(refreshToken)
            + "&client_id="
            + Urls.urlEncode(config.clientId())
            + "&client_secret="
            + Urls.urlEncode(config.clientSecret());
    JsonNode node = postForm(TOKEN_URL, body, "Google Ads token refresh");
    String rotated = node.path("refresh_token").asText(null);
    return toToken(node, rotated != null ? rotated : refreshToken);
  }

  // Revoking either token kills the whole grant on Google's side.
  public void revoke(String token) {
    postForm(REVOKE_URL, "token=" + Urls.urlEncode(token), "Google Ads token revoke");
  }

  private GoogleToken toToken(JsonNode node, String refreshToken) {
    long expiresIn = node.path("expires_in").asLong(0);
    // id_token is present only when the grant included the openid scope, and never on
    // a refresh response. The freemium onboarding reads it to identify the user.
    return new GoogleToken(
        node.path("access_token").asText(),
        refreshToken,
        expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null,
        node.path("scope").asText(""),
        node.path("id_token").asText(null));
  }

  public JsonNode postForm(String url, String formBody, String operation) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
            .build();
    return send(request, operation);
  }

  private JsonNode send(HttpRequest request, String operation) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode body =
          response.body() == null || response.body().isBlank()
              ? objectMapper.createObjectNode()
              : objectMapper.readTree(response.body());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return body;
      }
      // OAuth endpoints use {error, error_description}; the "error" value
      // (e.g. invalid_grant) doubles as the machine-readable status.
      String errorCode = body.path("error").asText(null);
      String message = body.path("error_description").asText(errorCode);
      log.warn("{} failed (HTTP {}): {}", operation, response.statusCode(), message);
      throw new GoogleAdsApiException(
          operation + " failed: " + message, response.statusCode(), errorCode);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("{} failed: {}", operation, e.getMessage());
      throw new GoogleAdsApiException(operation + " failed: " + e.getMessage(), 0, null);
    }
  }

  public record GoogleToken(
      String accessToken, String refreshToken, Instant expiresAt, String scope, String idToken) {}
}
