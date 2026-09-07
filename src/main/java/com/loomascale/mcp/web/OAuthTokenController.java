package com.loomascale.mcp.web;

import com.loomascale.mcp.oauth.OAuthTokenGrantService;
import com.loomascale.mcp.oauth.OAuthTokenGrantService.OAuthTokenException;
import com.loomascale.mcp.oauth.OAuthTokenGrantService.TokenResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// POST /oauth/token. Form-encoded per OAuth. Dispatches on grant_type;
// authorization_code and refresh_token only. Responses carry Cache-Control: no-store.
@Slf4j
@RestController
@RequiredArgsConstructor
public class OAuthTokenController {

  private final OAuthTokenGrantService grantService;

  @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<Map<String, Object>> token(
      @RequestParam(name = "grant_type", required = false) String grantType,
      @RequestParam(name = "client_id", required = false) String clientId,
      @RequestParam(required = false) String code,
      @RequestParam(name = "redirect_uri", required = false) String redirectUri,
      @RequestParam(name = "code_verifier", required = false) String codeVerifier,
      @RequestParam(name = "refresh_token", required = false) String refreshToken) {
    try {
      TokenResponse response;
      if ("authorization_code".equals(grantType)) {
        response = grantService.exchangeCode(clientId, code, redirectUri, codeVerifier);
      } else if ("refresh_token".equals(grantType)) {
        response = grantService.refresh(clientId, refreshToken);
      } else {
        return error("unsupported_grant_type", "Unsupported grant_type");
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("access_token", response.accessToken());
      body.put("token_type", response.tokenType());
      body.put("expires_in", response.expiresIn());
      body.put("refresh_token", response.refreshToken());
      body.put("scope", response.scope());
      return ResponseEntity.ok()
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .header(HttpHeaders.PRAGMA, "no-cache")
          .body(body);
    } catch (OAuthTokenException e) {
      log.warn("Token grant failed ({}): {}", e.error(), e.getMessage());
      return error(e.error(), e.getMessage());
    }
  }

  private ResponseEntity<Map<String, Object>> error(String error, String description) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("error_description", description);
    return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
  }
}
