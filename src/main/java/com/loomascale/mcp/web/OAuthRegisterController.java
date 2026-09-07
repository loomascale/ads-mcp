package com.loomascale.mcp.web;

import com.loomascale.mcp.oauth.OAuthClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loomascale.mcp.oauth.OAuthClient;
import com.loomascale.mcp.oauth.DynamicClientRegistrationService;
import com.loomascale.mcp.oauth.DynamicClientRegistrationService.InvalidRegistrationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// RFC 7591 dynamic client registration endpoint (public, rate-limited in the service).
@Slf4j
@RestController
@RequiredArgsConstructor
public class OAuthRegisterController {

  private final DynamicClientRegistrationService registrationService;

  // RFC 7591 uses snake_case (redirect_uris, client_name). Bind those explicitly —
  // the shared ObjectMapper is camelCase, so ChatGPT's payload would otherwise not map.
  @Data
  public static class RegisterRequest {
    @JsonProperty("client_name")
    private String clientName;

    @JsonProperty("redirect_uris")
    private List<String> redirectUris;
  }

  @PostMapping("/oauth/register")
  public ResponseEntity<?> register(
      @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
    try {
      OAuthClient client =
          registrationService.register(
              request.getClientName(), request.getRedirectUris(), clientIp(httpRequest));
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(
              Map.of(
                  "client_id", client.clientId(),
                  "client_id_issued_at", client.createdAt().getEpochSecond(),
                  "client_name", client.clientName(),
                  "redirect_uris", request.getRedirectUris(),
                  "token_endpoint_auth_method", "none",
                  "grant_types", List.of("authorization_code", "refresh_token"),
                  "response_types", List.of("code")));
    } catch (InvalidRegistrationException e) {
      log.warn("Client registration rejected: {}", e.getMessage());
      return ResponseEntity.badRequest()
          .body(Map.of("error", "invalid_client_metadata", "error_description", e.getMessage()));
    }
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
