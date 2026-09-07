package com.loomascale.googleads.mcp.connect;

import com.loomascale.googleads.mcp.connect.GoogleAdsConnectService.ConnectOutcome;
import com.loomascale.mcp.consent.ResourceOwnerAuthenticator;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.AdsTarget;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Connecting a Google Ads account, and choosing which one tool calls should use.
//
// Separate from the MCP OAuth flow, and easy to confuse with it: that one is a client
// asking for access to THIS server, while this is THIS server asking Google for access to
// the operator's ad accounts. Two OAuth flows in opposite directions.
//
// Authenticated with the same ResourceOwnerAuthenticator as consent, because connecting an
// ad account is at least as sensitive as approving a client.
@Slf4j
@RestController
@RequiredArgsConstructor
public class GoogleAdsConnectController {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final GoogleAdsConnectService connectService;
  private final AdsConnectionStore connectionStore;
  private final ResourceOwnerAuthenticator resourceOwner;
  private final ConnectPageRenderer renderer;

  @GetMapping(value = "/connections", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> connections(HttpServletRequest request) {
    Optional<String> userId = resourceOwner.authenticate(request);
    if (userId.isEmpty()) {
      return html(HttpStatus.UNAUTHORIZED, renderer.signIn(null));
    }
    Optional<AdsConnection> connection =
        connectionStore.find(userId.get(), AdsPlatforms.GOOGLE_ADS);
    List<AdsTarget> targets = connection.map(connectionStore::targets).orElseGet(List::of);
    return html(HttpStatus.OK, renderer.connections(connection.orElse(null), targets));
  }

  @PostMapping("/connect/google")
  public ResponseEntity<String> start(HttpServletRequest request) {
    if (resourceOwner.authenticate(request).isEmpty()) {
      return html(HttpStatus.UNAUTHORIZED, renderer.signIn("That password was not correct."));
    }
    // The state parameter is a CSRF defence, not a data channel: Google echoes it back and
    // the callback checks it, so a forged callback with somebody else's code is refused.
    String state = randomState();
    request.getSession(true).setAttribute(STATE_ATTRIBUTE, state);
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(URI.create(connectService.authorizationUrl(state)))
        .build();
  }

  @GetMapping(value = "/connect/google/callback", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> callback(
      HttpServletRequest request,
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String error) {

    if (error != null) {
      // The user pressed Deny, or Google refused. Not an exception — it is a normal
      // outcome that needs explaining rather than a stack trace.
      log.debug("Google consent returned error={}", error);
      return html(HttpStatus.OK, renderer.message("Google did not grant access: " + error));
    }

    Object expected = request.getSession(true).getAttribute(STATE_ATTRIBUTE);
    if (state == null || expected == null || !state.equals(expected)) {
      log.warn("Rejected Google callback with a mismatched state parameter");
      return html(
          HttpStatus.BAD_REQUEST,
          renderer.message(
              "This callback did not match the request that started it. Start again from"
                  + " the connections page."));
    }
    request.getSession().removeAttribute(STATE_ATTRIBUTE);

    Optional<String> userId = resourceOwner.authenticate(request);
    if (userId.isEmpty()) {
      return html(HttpStatus.UNAUTHORIZED, renderer.signIn(null));
    }
    if (code == null || code.isBlank()) {
      return html(
          HttpStatus.BAD_REQUEST, renderer.message("Google returned no authorization code."));
    }

    ConnectOutcome outcome = connectService.completeConnect(userId.get(), code);
    if (outcome.scopeDeclined()) {
      return html(
          HttpStatus.OK,
          renderer.message(
              "The Google Ads permission was not granted, so nothing was saved. Connect"
                  + " again and leave the Google Ads box ticked — it is a separate tick box"
                  + " from signing in."));
    }
    if (outcome.needsGoogleAdsAccount()) {
      return html(
          HttpStatus.OK,
          renderer.message(
              "Your Google account is connected, but it does not own a Google Ads account"
                  + " yet — or the only accounts it can reach are manager (MCC) accounts,"
                  + " which cannot run ads. Create one at https://ads.google.com/ and then"
                  + " reload the connections page. You will not need to sign in again."));
    }
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/connections")).build();
  }

  @PostMapping("/connections/select")
  public ResponseEntity<String> select(
      HttpServletRequest request, @RequestParam("target_id") String targetId) {
    Optional<String> userId = resourceOwner.authenticate(request);
    if (userId.isEmpty()) {
      return html(HttpStatus.UNAUTHORIZED, renderer.signIn("That password was not correct."));
    }
    try {
      connectService.selectAccount(userId.get(), targetId);
    } catch (IllegalArgumentException | IllegalStateException e) {
      return html(HttpStatus.BAD_REQUEST, renderer.message(e.getMessage()));
    }
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/connections")).build();
  }

  private static final String STATE_ATTRIBUTE = "google_connect_state";

  private static String randomState() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private ResponseEntity<String> html(HttpStatus status, String body) {
    return ResponseEntity.status(status)
        .contentType(MediaType.TEXT_HTML)
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        .body(body);
  }
}
