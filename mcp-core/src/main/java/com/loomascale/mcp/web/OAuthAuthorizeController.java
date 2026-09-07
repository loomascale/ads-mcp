package com.loomascale.mcp.web;

import com.loomascale.mcp.oauth.OAuthAuthorizationService;
import com.loomascale.mcp.oauth.OAuthAuthorizationService.AuthorizationRedirectException;
import com.loomascale.mcp.oauth.OAuthAuthorizationService.InvalidClientException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// GET /oauth/authorize. On a valid request the browser is 302'd to the frontend
// consent page. Invalid client/redirect_uri → 400 HTML (never a redirect, so we
// don't bounce a code to an unvalidated URI). Other spec errors → redirect back
// to the validated client redirect_uri with error=.
@Slf4j
@RestController
@RequiredArgsConstructor
public class OAuthAuthorizeController {

  private final OAuthAuthorizationService authorizationService;

  @GetMapping("/oauth/authorize")
  public ResponseEntity<String> authorize(
      @RequestParam(name = "client_id", required = false) String clientId,
      @RequestParam(name = "redirect_uri", required = false) String redirectUri,
      @RequestParam(name = "response_type", required = false) String responseType,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String state,
      @RequestParam(name = "code_challenge", required = false) String codeChallenge,
      @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
      @RequestParam(required = false) String resource,
      @RequestParam(defaultValue = "en") String lang) {
    try {
      String consentUrl =
          authorizationService.beginAuthorization(
              clientId,
              redirectUri,
              responseType,
              scope,
              state,
              codeChallenge,
              codeChallengeMethod,
              resource,
              lang);
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(consentUrl)).build();
    } catch (InvalidClientException e) {
      log.warn("Authorize rejected (400): {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .contentType(MediaType.TEXT_HTML)
          .body("<h1>Invalid authorization request</h1><p>" + escape(e.getMessage()) + "</p>");
    } catch (AuthorizationRedirectException e) {
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(e.redirectUrl())).build();
    }
  }

  private String escape(String value) {
    return value == null
        ? ""
        : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
