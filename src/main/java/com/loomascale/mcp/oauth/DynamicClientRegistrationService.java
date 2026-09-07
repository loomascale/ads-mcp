package com.loomascale.mcp.oauth;

import java.time.Instant;import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.util.ExpiringCache;
import com.loomascale.mcp.spi.McpAlertSink;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// RFC 7591 Dynamic Client Registration for MCP clients (ChatGPT, Claude, ...). Open
// per the MCP spec: any https redirect_uri may register unless OAUTH_REDIRECT_HOSTS
// pins a host allowlist. What still guards it — redirect URIs must be https with no
// fragment and no userinfo, the payload is size-capped, registration is rate-limited
// per IP, and the consent screen shows the user which host the code will be sent to.
@Slf4j
@RequiredArgsConstructor
public class DynamicClientRegistrationService {

  private static final int MAX_REGISTRATIONS_PER_IP_PER_HOUR = 10;
  private static final int MAX_REDIRECT_URIS = 5;
  private static final int MAX_REDIRECT_URI_LENGTH = 512;
  private static final int MAX_CLIENT_NAME_LENGTH = 120;

  private final OAuthClientStore clientStore;
  private final OAuthProperties properties;
  private final ObjectMapper objectMapper;
  private final McpAlertSink alertSink;

  private final ExpiringCache<String, AtomicInteger> registrationsByIp =
      new ExpiringCache<>(Duration.ofHours(1), 10_000);

  public OAuthClient register(String clientName, List<String> redirectUris, String clientIp) {
    enforceRateLimit(clientIp);
    if (redirectUris == null || redirectUris.isEmpty()) {
      throw new InvalidRegistrationException("redirect_uris is required");
    }
    if (redirectUris.size() > MAX_REDIRECT_URIS) {
      throw new InvalidRegistrationException(
          "at most " + MAX_REDIRECT_URIS + " redirect_uris are allowed");
    }
    for (String uri : redirectUris) {
      validateRedirectUri(uri);
    }

    String id = java.util.UUID.randomUUID().toString();
    OAuthClient client =
        new OAuthClient(
            id,
            "mcp_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            // client_name is attacker-controlled and renders on the consent screen, so cap it.
            sanitizeClientName(clientName),
            List.copyOf(redirectUris),
            "none",
            String.join(" ", OAuthScopes.SUPPORTED),
            Instant.now());
    clientStore.save(client);

    alertSink.alert(
        "MCP client registered\nName: "
            + client.clientName()
            + "\nRedirects: "
            + String.join(", ", redirectUris));
    log.info("Registered MCP client {} ({})", client.clientId(), client.clientName());
    return client;
  }


  private void validateRedirectUri(String uri) {
    if (uri == null || uri.isBlank()) {
      throw new InvalidRegistrationException("redirect_uri must not be blank");
    }
    if (uri.length() > MAX_REDIRECT_URI_LENGTH) {
      throw new InvalidRegistrationException("redirect_uri is too long");
    }
    URI parsed;
    try {
      parsed = URI.create(uri);
    } catch (Exception e) {
      throw new InvalidRegistrationException("Invalid redirect_uri: " + uri);
    }
    if (!"https".equalsIgnoreCase(parsed.getScheme())) {
      throw new InvalidRegistrationException("redirect_uri must be https: " + uri);
    }
    if (parsed.getFragment() != null) {
      throw new InvalidRegistrationException("redirect_uri must not contain a fragment: " + uri);
    }
    if (parsed.getUserInfo() != null) {
      // https://claude.ai@evil.example/cb reads as claude.ai in a truncated UI.
      throw new InvalidRegistrationException("redirect_uri must not contain userinfo: " + uri);
    }
    String host = parsed.getHost();
    if (host == null) {
      throw new InvalidRegistrationException("redirect_uri has no host: " + uri);
    }
    List<String> allowlist = properties.redirectHostsAllowlist();
    if (!allowlist.isEmpty() && allowlist.stream().noneMatch(h -> hostMatches(host, h))) {
      throw new InvalidRegistrationException("redirect_uri host not allowed: " + host);
    }
  }

  private String sanitizeClientName(String clientName) {
    if (clientName == null || clientName.isBlank()) {
      return "MCP Client";
    }
    String trimmed = clientName.trim();
    return trimmed.length() > MAX_CLIENT_NAME_LENGTH
        ? trimmed.substring(0, MAX_CLIENT_NAME_LENGTH)
        : trimmed;
  }

  private boolean hostMatches(String host, String allowed) {
    return host.equalsIgnoreCase(allowed) || host.toLowerCase().endsWith("." + allowed.toLowerCase());
  }

  private void enforceRateLimit(String clientIp) {
    String ip = clientIp == null ? "unknown" : clientIp;
    AtomicInteger counter = registrationsByIp.computeIfAbsent(ip, AtomicInteger::new);
    if (counter.incrementAndGet() > MAX_REGISTRATIONS_PER_IP_PER_HOUR) {
      throw new InvalidRegistrationException("Too many registrations, try again later");
    }
  }

  public static class InvalidRegistrationException extends RuntimeException {
    public InvalidRegistrationException(String message) {
      super(message);
    }
  }
}
