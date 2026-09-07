package com.loomascale.mcp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.mcp.oauth.OAuthAccessTokenService;
import com.loomascale.mcp.oauth.OAuthAccessTokenService.AccessTokenClaims;
import com.loomascale.mcp.oauth.OAuthAccessTokenService.InvalidTokenException;
import com.loomascale.mcp.oauth.OAuthProperties;
import com.loomascale.mcp.protocol.McpProtocolService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// MCP streamable-HTTP endpoint. Stateless: one JSON response per POST. Deliberately
// OUTSIDE /api/** so it gets no CORS (server-to-server from ChatGPT). Every request
// is authenticated with an MCP access token; missing/invalid → 401 + WWW-Authenticate
// pointing at the protected-resource metadata (how ChatGPT discovers the AS).
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

  private final OAuthAccessTokenService accessTokenService;
  private final OAuthProperties properties;
  private final McpProtocolService protocolService;
  private final ObjectMapper objectMapper;

  @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<JsonNode> handle(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
      @RequestBody JsonNode body) {

    // DNS-rebinding defense: a legitimate MCP client is server-to-server and sends
    // no browser Origin. Reject any request that carries one.
    if (origin != null && !origin.isBlank()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    if (!accessTokenService.isConfigured()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    AccessTokenClaims claims;
    try {
      if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw new InvalidTokenException("Missing bearer token");
      }
      claims = accessTokenService.validate(authHeader.substring("Bearer ".length()).trim());
    } catch (InvalidTokenException e) {
      log.debug("MCP token rejected: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate())
          .build();
    }

    Optional<ObjectNode> response = protocolService.handle(body, claims);
    // Notifications get no body.
    return response
        .<ResponseEntity<JsonNode>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.accepted().build());
  }

  // GET is not supported by this stateless server (no server-initiated SSE stream).
  @GetMapping("/mcp")
  public ResponseEntity<Void> getNotAllowed() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }

  private String wwwAuthenticate() {
    return "Bearer resource_metadata=\""
        + properties.getIssuer()
        + "/.well-known/oauth-protected-resource/mcp\", error=\"invalid_token\"";
  }
}
