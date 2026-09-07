package com.loomascale.mcp.web;

import com.loomascale.mcp.consent.ResourceOwnerAuthenticator;
import com.loomascale.mcp.oauth.OAuthAuthorizationService;
import com.loomascale.mcp.oauth.OAuthAuthorizationService.ConsentInfo;
import com.loomascale.mcp.oauth.OAuthAuthorizationService.InvalidClientException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// JSON consent endpoints, for a deployment that renders its own consent screen. The
// bundled server-rendered page (BuiltinConsentPageController) is an alternative front end
// over the same authorization service.
//
// Who the resource owner is comes from ResourceOwnerAuthenticator rather than from any
// particular login mechanism: that is the one part of the flow a library cannot decide.
// Under /api/** so a host's existing CORS mapping for its own front end applies.
@Slf4j
@RestController
@RequestMapping("/api/oauth/consent")
@RequiredArgsConstructor
public class OAuthConsentApiController {

  private final OAuthAuthorizationService authorizationService;
  private final ResourceOwnerAuthenticator resourceOwner;

  @GetMapping("/{requestId}")
  public ResponseEntity<?> info(HttpServletRequest request, @PathVariable String requestId) {
    if (resourceOwner.authenticate(request).isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    Optional<ConsentInfo> info = authorizationService.getPending(requestId);
    if (info.isEmpty()) {
      // GONE rather than NOT_FOUND: the request was real and has expired, and the page
      // should tell the user to start again from their client rather than retry.
      return ResponseEntity.status(HttpStatus.GONE)
          .body(Map.of("error", "Authorization request expired or unknown"));
    }
    return ResponseEntity.ok(
        Map.of(
            "clientName", info.get().clientName(),
            "scopes", info.get().scopes(),
            "redirectHost", info.get().redirectHost()));
  }

  @PostMapping("/{requestId}/approve")
  public ResponseEntity<?> approve(HttpServletRequest request, @PathVariable String requestId) {
    Optional<String> userId = resourceOwner.authenticate(request);
    if (userId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    try {
      String redirectUrl = authorizationService.approve(requestId, userId.get());
      log.debug("Consent approved for request {}", requestId);
      return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    } catch (InvalidClientException e) {
      return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/{requestId}/deny")
  public ResponseEntity<?> deny(HttpServletRequest request, @PathVariable String requestId) {
    if (resourceOwner.authenticate(request).isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    try {
      return ResponseEntity.ok(Map.of("redirectUrl", authorizationService.deny(requestId)));
    } catch (InvalidClientException e) {
      return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", e.getMessage()));
    }
  }
}
