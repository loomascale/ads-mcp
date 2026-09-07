package com.loomascale.mcp.web;

import com.loomascale.mcp.oauth.OAuthProperties;
import com.loomascale.mcp.oauth.OAuthScopes;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// OAuth discovery documents. ChatGPT reads oauth-protected-resource (derived from
// the MCP resource URL) to find the authorization server, then reads
// oauth-authorization-server to find the endpoints.
@RestController
@RequiredArgsConstructor
public class WellKnownController {

  private final OAuthProperties properties;

  @GetMapping("/.well-known/oauth-authorization-server")
  public Map<String, Object> authorizationServerMetadata() {
    String issuer = properties.getIssuer();
    return Map.of(
        "issuer", issuer,
        "authorization_endpoint", issuer + "/oauth/authorize",
        "token_endpoint", issuer + "/oauth/token",
        "registration_endpoint", issuer + "/oauth/register",
        "response_types_supported", List.of("code"),
        "grant_types_supported", List.of("authorization_code", "refresh_token"),
        "code_challenge_methods_supported", List.of("S256"),
        "token_endpoint_auth_methods_supported", List.of("none"),
        "scopes_supported", OAuthScopes.SUPPORTED);
  }

  // Both the bare and the /mcp-suffixed forms — RFC 9728 lets the client derive
  // the suffixed path from the resource URL, but some clients try the bare path.
  @GetMapping({
    "/.well-known/oauth-protected-resource",
    "/.well-known/oauth-protected-resource/mcp"
  })
  public Map<String, Object> protectedResourceMetadata() {
    return Map.of(
        "resource", properties.resource(),
        "authorization_servers", List.of(properties.getIssuer()),
        "bearer_methods_supported", List.of("header"),
        "scopes_supported", OAuthScopes.SUPPORTED);
  }
}
