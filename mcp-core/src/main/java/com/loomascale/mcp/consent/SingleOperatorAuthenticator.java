package com.loomascale.mcp.consent;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

// Resource-owner authentication for a server with exactly one user: whoever runs it.
//
// This is what makes a fresh clone able to complete an OAuth flow at all. Without it the
// consent endpoint could never identify an approver, and no MCP client could connect —
// so "self-hostable" would not be true.
//
// The operator proves themselves with a single configured password, submitted by the
// bundled consent page or sent as a bearer token. Every authorized request then belongs to
// one fixed user id, which is legitimate here because there is exactly one account.
//
// This is emphatically NOT a multi-user login. A deployment with real users replaces this
// bean with one that consults whatever already knows who its users are.
@Slf4j
public class SingleOperatorAuthenticator implements ResourceOwnerAuthenticator {

  public static final String PASSWORD_PARAM = "operator_password";

  private final byte[] expectedPassword;
  private final String userId;

  public SingleOperatorAuthenticator(String operatorPassword, String userId) {
    this.expectedPassword = operatorPassword.getBytes(StandardCharsets.UTF_8);
    this.userId = userId;
  }

  @Override
  public Optional<String> authenticate(HttpServletRequest request) {
    String supplied = suppliedPassword(request);
    if (supplied == null || supplied.isEmpty()) {
      return Optional.empty();
    }
    // Constant-time comparison: a length-or-prefix-sensitive equals() on a secret compared
    // per HTTP request is enough to recover it a byte at a time.
    if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expectedPassword)) {
      log.warn("Consent rejected: wrong operator password from {}", request.getRemoteAddr());
      return Optional.empty();
    }
    return Optional.of(userId);
  }

  private String suppliedPassword(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      return header.substring("Bearer ".length()).trim();
    }
    if (header != null && header.startsWith("Basic ")) {
      try {
        String decoded =
            new String(
                Base64.getDecoder().decode(header.substring("Basic ".length()).trim()),
                StandardCharsets.UTF_8);
        // Basic carries user:password; only the password half is checked, since there is
        // only one account and its name is not a secret.
        int colon = decoded.indexOf(':');
        return colon < 0 ? decoded : decoded.substring(colon + 1);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return request.getParameter(PASSWORD_PARAM);
  }
}
