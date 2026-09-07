package com.loomascale.mcp.web;

import com.loomascale.mcp.consent.ConsentPageRenderer;
import com.loomascale.mcp.consent.ResourceOwnerAuthenticator;
import com.loomascale.mcp.oauth.OAuthAuthorizationService;
import com.loomascale.mcp.oauth.OAuthAuthorizationService.ConsentInfo;
import com.loomascale.mcp.oauth.OAuthAuthorizationService.InvalidClientException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The bundled consent screen: a GET that renders the page and a POST that records the
// decision. This exists so a fresh clone can complete an OAuth flow without anyone
// building a front end first.
//
// Outside /api/** on purpose: this is a browser page, not an API, and it must not inherit
// a host's CORS configuration for its own front end.
@Slf4j
@RestController
@RequiredArgsConstructor
public class BuiltinConsentPageController {

  private final OAuthAuthorizationService authorizationService;
  private final ResourceOwnerAuthenticator resourceOwner;
  private final ConsentPageRenderer renderer;

  @GetMapping(value = "${mcp.consent.path:/oauth/consent}", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> page(@RequestParam("request_id") String requestId) {
    Optional<ConsentInfo> info = authorizationService.getPending(requestId);
    if (info.isEmpty()) {
      return expired();
    }
    return html(HttpStatus.OK, renderer.renderHtml(info.get(), requestId, null));
  }

  // One endpoint for both buttons: the decision travels in the submitted form, so approve
  // and deny cannot diverge in how they authenticate or how they handle an expired request.
  @PostMapping(value = "${mcp.consent.path:/oauth/consent}/{requestId}/decide")
  public ResponseEntity<String> decide(
      HttpServletRequest request,
      @PathVariable String requestId,
      @RequestParam("decision") String decision) {

    Optional<ConsentInfo> info = authorizationService.getPending(requestId);
    if (info.isEmpty()) {
      return expired();
    }

    // Authenticate before acting on either button. A denial is also a decision only the
    // resource owner may take: an unauthenticated caller must not be able to cancel
    // somebody's pending authorization.
    Optional<String> userId = resourceOwner.authenticate(request);
    if (userId.isEmpty()) {
      return html(
          HttpStatus.UNAUTHORIZED,
          renderer.renderHtml(info.get(), requestId, "That password was not correct."));
    }

    try {
      String redirectUrl =
          "approve".equals(decision)
              ? authorizationService.approve(requestId, userId.get())
              : authorizationService.deny(requestId);
      log.debug("Consent {} for request {}", decision, requestId);
      // 303 so the browser follows with GET: the client's redirect_uri is not expecting
      // the form POST to be replayed against it.
      return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(redirectUrl)).build();
    } catch (InvalidClientException e) {
      return html(HttpStatus.GONE, messagePage("Authorization request is no longer valid."));
    }
  }

  private ResponseEntity<String> expired() {
    return html(
        HttpStatus.GONE,
        messagePage("This authorization request has expired. Start again from your client."));
  }

  // A minimal standalone message page, for the cases where there is no pending request
  // left to render the real screen from.
  private String messagePage(String message) {
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        + "<meta name=\"robots\" content=\"noindex, nofollow\">"
        + "<title>Authorization</title></head><body style=\"font:16px system-ui;"
        + "margin:0;min-height:100vh;display:grid;place-items:center\"><p>"
        + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        + "</p></body></html>";
  }

  private ResponseEntity<String> html(HttpStatus status, String body) {
    return ResponseEntity.status(status)
        .contentType(MediaType.TEXT_HTML)
        // The page reflects a client name and a redirect host; no caching, and no
        // referrer leaking the request id onward.
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        .body(body);
  }
}
