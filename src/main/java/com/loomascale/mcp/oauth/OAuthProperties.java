package com.loomascale.mcp.oauth;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Config for the minimal OAuth 2.1 authorization server that fronts the MCP
// endpoint. The access-token signing secret is env-only (no committed default)
// and MUST be distinct from jwt.secret so login tokens and MCP tokens are never
// interchangeable.
@Getter
@Component
public class OAuthProperties {

  @Value("${oauth.issuer:https://api.loomascale.com}")
  private String issuer;

  @Value("${oauth.access-token-secret:}")
  private String accessTokenSecret;

  @Value("${oauth.access-token-ttl-seconds:900}")
  private long accessTokenTtlSeconds;

  @Value("${oauth.refresh-token-ttl-days:90}")
  private long refreshTokenTtlDays;

  @Value("${oauth.code-ttl-seconds:120}")
  private long codeTtlSeconds;

  // Comma-separated allowlist of hosts a registered redirect_uri may use. Empty
  // (the default) disables the allowlist: any https redirect_uri may register, which
  // is what the MCP spec expects of an open dynamic-registration endpoint. The other
  // redirect_uri checks (https-only, no fragment, no userinfo) and the per-IP rate
  // limit still apply, and the consent screen shows the redirect host to the user.
  @Value("${oauth.redirect-hosts-allowlist:}")
  private String redirectHostsAllowlist;

  public boolean isConfigured() {
    return accessTokenSecret != null && !accessTokenSecret.isBlank();
  }

  // The protected resource these tokens are minted for (the MCP endpoint).
  public String resource() {
    return issuer + "/mcp";
  }

  public List<String> redirectHostsAllowlist() {
    if (redirectHostsAllowlist == null || redirectHostsAllowlist.isBlank()) {
      return List.of();
    }
    return Arrays.stream(redirectHostsAllowlist.split(",")).map(String::trim).toList();
  }
}
