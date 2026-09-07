package com.loomascale.googleads.mcp.service;

import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.GoogleAdsApiException;
import com.loomascale.googleads.client.GoogleAdsRequestLog;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsTarget;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.tool.McpToolException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Business layer the google_* ads tools call — the Google twin of MetaAdsService.
// Owns refresh-on-use (Google access tokens live ~1 hour, so every tool call may
// need a refresh first), customer-id resolution, retry, and turning raw API
// failures into safe messages with token strings scrubbed. Domain failures are
// thrown as McpToolException, which the MCP protocol layer already handles.
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAdsService {

  // Refresh this far before expiry so a token never dies mid-call.
  private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

  private final GoogleAdsApiClient adsClient;
  private final AdsConnectionStore connectionStore;
  private final ProductBranding branding;

  private static final int MAX_ATTEMPTS = 3;

  private final Retry adsRetry = buildRetry();

  // Each retried attempt logs its own GADS-ERR block, so without this line one failure
  // reads as three unrelated bugs.
  private static Retry buildRetry() {
    Retry retry =
        Retry.of(
            "googleAds",
            RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(2), 2))
                .retryOnException(
                    e ->
                        e instanceof GoogleAdsApiException
                            && ((GoogleAdsApiException) e).isRetryable())
                .build());
    retry
        .getEventPublisher()
        .onRetry(
            event ->
                log.warn(
                    "GADS-RETRY attempt {}/{} after: {}",
                    event.getNumberOfRetryAttempts() + 1,
                    MAX_ATTEMPTS,
                    event.getLastThrowable() == null
                        ? "-"
                        : event.getLastThrowable().getMessage()));
    return retry;
  }

  public GoogleAdsApiClient client() {
    return adsClient;
  }

  // Returns a live access token, refreshing first when the stored one is expired
  // or about to expire. Only a refusal from Google (invalid_grant, or no refresh
  // token left) expires the connection — a token endpoint that is merely down
  // must not, or a Google outage would log every user out permanently.
  public String decryptToken(AdsConnection connection) {
    return connectionStore.freshAccessToken(connection);
  }

  // Resolves the target customer account: an explicit arg (normalized and validated
  // against the stored snapshot) or the connection's selected target.
  public String resolveCustomerId(AdsConnection connection, String requestedCustomerId) {
    if (requestedCustomerId != null && !requestedCustomerId.isBlank()) {
      String normalized = requestedCustomerId.replace("-", "").trim();
      boolean known = readTargets(connection).stream().anyMatch(t -> normalized.equals(t.id()));
      if (!known) {
        throw new McpToolException(
            "Google Ads account "
                + requestedCustomerId
                + " is not in your connected accounts. Use google_list_ad_accounts to see them.");
      }
      return normalized;
    }
    if (connection.selectedTargetId() != null && !connection.selectedTargetId().isBlank()) {
      return connection.selectedTargetId();
    }
    throw new McpToolException(
        "No Google Ads account selected. Choose one in the dashboard Connections page, or pass"
            + " customer_id.");
  }

  // The login-customer-id header for a customer: the accessible customer (usually
  // an MCC) it was discovered under, recorded per target at connect time in the
  // snapshot's pageAccessTokenEnc slot. Null (header omitted) when unknown — fine
  // for directly accessible accounts.
  public String loginCustomerId(AdsConnection connection, String customerId) {
    Optional<AdsTarget> target =
        readTargets(connection).stream().filter(t -> customerId.equals(t.id())).findFirst();
    if (target.isEmpty() || target.get().loginHint() == null) {
      return null;
    }
    return target.get().loginHint();
  }

  // ISO currency of the account, for labelling every money value that reaches the
  // model. Stored on the connection after the first lookup. Returns null when it
  // cannot be determined — an unlabelled amount beats failing the whole tool, and
  // beats the old behaviour of printing a "$" on a UAH account.
  public String currencyCode(
      AdsConnection connection, String token, String customerId, String loginCustomerId) {
    Optional<String> stored = connectionStore.storedCurrency(connection, customerId);
    if (stored.isPresent()) {
      return stored.get();
    }
    try {
      String currency =
          call(connection, () -> adsClient.customerCurrency(token, customerId, loginCustomerId));
      connectionStore.rememberCurrency(connection, customerId, currency);
      return currency;
    } catch (RuntimeException e) {
      log.warn(
          "Could not resolve currency for Google Ads customer {}: {}", customerId, e.getMessage());
      return null;
    }
  }

  // Runs a Google Ads call with retry and uniform error translation.
  public <T> T call(AdsConnection connection, Supplier<T> apiCall) {
    try {
      return adsRetry.executeSupplier(apiCall);
    } catch (GoogleAdsApiException e) {
      if (e.isNotAdsUser()) {
        // The token is fine — the Google account behind it owns no Ads account. Do NOT
        // expire the connection: reconnecting the same account would fail identically.
        throw new McpToolException(
            "This Google account is not linked to any Google Ads account. Create one at"
                + " https://ads.google.com/, then open "
                + branding.connectionsUrl()
                + " and press \"Check again\" — no second sign-in needed. If your ads live"
                + " under a different Google login, connect that one instead.");
      }
      if (e.isAuthError()) {
        markExpired(connection);
        throw new McpToolException(
            "Google Ads access was revoked or expired. Reconnect at " + branding.connectionsUrl());
      }
      if (e.isPermissionDenied()) {
        // The token works — the developer token or the user's role on the account
        // is what Google refused. Do NOT expire the connection.
        throw new McpToolException(deniedMessage(e));
      }
      throw new McpToolException(scrub(e.getMessage()));
    }
  }

  private String deniedMessage(GoogleAdsApiException e) {
    String detail = scrub(e.getMessage());
    if (detail.contains("DEVELOPER_TOKEN_NOT_APPROVED")
        || detail.contains("DEVELOPER_TOKEN_PROHIBITED")) {
      return "Google rejected the request because "
          + branding.productName()
          + "'s Google Ads API access level does not cover this account yet — nothing you can"
          + " change on your side. Google says: "
          + detail;
    }
    return "Google refused this request. Your Google user may lack access to this Google Ads"
        + " account, or the account may not accept API changes. Google says: "
        + detail
        + " If access changed recently, reconnect at "
        + branding.connectionsUrl();
  }

  private void markExpired(AdsConnection connection) {
    connectionStore.markExpired(connection.id());
  }

  private List<AdsTarget> readTargets(AdsConnection connection) {
    return connectionStore.targets(connection);
  }

  // Google access tokens are ya29.…, refresh tokens 1//…; neither may reach the model.
  private String scrub(String message) {
    if (message == null) {
      return "Google Ads API request failed.";
    }
    return GoogleAdsRequestLog.scrub(message);
  }
}
