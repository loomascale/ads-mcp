package com.loomascale.mcp.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loomascale.mcp.oauth.OAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// A guard nobody has watched fail is not a guard. These assert that a server which cannot
// possibly work refuses to start, and says which variable to set.
class McpStartupCheckTest {

  private OAuthProperties oauth(String secret, String issuer) {
    OAuthProperties properties = new OAuthProperties();
    ReflectionTestUtils.setField(properties, "accessTokenSecret", secret);
    ReflectionTestUtils.setField(properties, "issuer", issuer);
    return properties;
  }

  private McpProperties mcp(String consentMode, String operatorPassword) {
    McpProperties properties = new McpProperties();
    properties.getConsent().setMode(consentMode);
    properties.getConsent().setOperatorPassword(operatorPassword);
    return properties;
  }

  @Test
  void refusesToStartWithoutATokenSigningSecret() {
    McpStartupCheck check =
        new McpStartupCheck(oauth("", "https://mcp.example.com"), mcp("builtin", "pw"), true, true);

    IllegalStateException e = assertThrows(IllegalStateException.class, check::afterPropertiesSet);

    assertTrue(e.getMessage().contains("oauth.access-token-secret"), e.getMessage());
    // The message has to be actionable, not merely correct.
    assertTrue(e.getMessage().contains("openssl rand"), e.getMessage());
  }

  // Without an operator password the bundled consent page can authorize nobody, so no
  // client could ever be connected. Starting up would only defer the discovery.
  @Test
  void refusesToStartWhenTheBundledConsentPageCannotAuthorizeAnyone() {
    McpStartupCheck check =
        new McpStartupCheck(
            oauth("secret", "https://mcp.example.com"), mcp("builtin", ""), true, true);

    IllegalStateException e = assertThrows(IllegalStateException.class, check::afterPropertiesSet);

    assertTrue(e.getMessage().contains("mcp.consent.operator-password"), e.getMessage());
    assertTrue(e.getMessage().contains("external"), "should mention the alternative");
  }

  // A deployment serving its own consent screen legitimately has no operator password.
  @Test
  void externalConsentModeNeedsNoOperatorPassword() {
    McpProperties properties = mcp("external", "");
    properties.getConsent().setUrl("https://example.com/consent?request_id={request_id}");

    McpStartupCheck check =
        new McpStartupCheck(oauth("secret", "https://mcp.example.com"), properties, true, true);

    assertDoesNotThrow(check::afterPropertiesSet);
  }

  // The encryption key is only needed by the built-in store, so a host that supplies its
  // own must not be blocked by its absence.
  @Test
  void aMissingEncryptionKeyIsToleratedWhenTheHostSuppliesItsOwnStore() {
    McpStartupCheck check =
        new McpStartupCheck(
            oauth("secret", "https://mcp.example.com"), mcp("builtin", "pw"), false, false);

    assertDoesNotThrow(check::afterPropertiesSet);
  }

  @Test
  void aFullyConfiguredServerStarts() {
    McpStartupCheck check =
        new McpStartupCheck(
            oauth("secret", "https://mcp.example.com"), mcp("builtin", "pw"), true, true);

    assertDoesNotThrow(check::afterPropertiesSet);
  }
}
