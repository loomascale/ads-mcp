package com.loomascale.mcp.autoconfigure;

import com.loomascale.mcp.oauth.OAuthProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

// Reports missing configuration once, at startup, in terms an operator can act on.
//
// Spring's own bind failures are accurate and nearly unreadable — a wall of
// BindValidationException that a self-hoster hitting it on a first run will simply give up
// on. Worse, several of these values are not required in every deployment (the encryption
// key is only needed by the built-in store), so making them hard @NotBlank constraints
// would refuse to start servers that are correctly configured for their own setup.
//
// So: a fatal error only for what is required in every case, and a specific WARN for what
// is conditionally required, naming the variable and what breaks without it.
@Slf4j
public class McpStartupCheck implements InitializingBean {

  private final OAuthProperties oauthProperties;
  private final McpProperties mcpProperties;
  private final boolean tokenCipherConfigured;
  private final boolean usingBuiltinConnectionStore;

  public McpStartupCheck(
      OAuthProperties oauthProperties,
      McpProperties mcpProperties,
      boolean tokenCipherConfigured,
      boolean usingBuiltinConnectionStore) {
    this.oauthProperties = oauthProperties;
    this.mcpProperties = mcpProperties;
    this.tokenCipherConfigured = tokenCipherConfigured;
    this.usingBuiltinConnectionStore = usingBuiltinConnectionStore;
  }

  @Override
  public void afterPropertiesSet() {
    List<String> fatal = new ArrayList<>();

    if (!oauthProperties.isConfigured()) {
      fatal.add(
          """
          oauth.access-token-secret is not set (OAUTH_ACCESS_TOKEN_SECRET).
              Without it no access token can be signed, so /mcp answers 503 to everything.
              Generate one with:  openssl rand -base64 48""");
    }

    if ("builtin".equalsIgnoreCase(mcpProperties.getConsent().getMode())
        && mcpProperties.getConsent().getOperatorPassword().isBlank()) {
      fatal.add(
          """
          mcp.consent.operator-password is not set (MCP_CONSENT_OPERATOR_PASSWORD).
              The bundled consent page cannot authorize anyone without it, so no client
              could ever be connected. Generate one with:  openssl rand -base64 24
              (Set mcp.consent.mode=external if you serve your own consent screen.)""");
    }

    if (!fatal.isEmpty()) {
      // Fail at startup rather than at the first request: a server that cannot complete
      // an authorization is not usable, and discovering that from a client is worse.
      throw new IllegalStateException(
          "mcp-core is missing required configuration:\n\n  * "
              + String.join("\n\n  * ", fatal)
              + "\n\nSee .env.example for every variable and how to generate it.\n");
    }

    if (usingBuiltinConnectionStore && !tokenCipherConfigured) {
      log.warn(
          "mcp.token-encryption-key is not set (MCP_TOKEN_ENCRYPTION_KEY), and the built-in"
              + " credential store is in use. Storing or reading a credential will fail until"
              + " it is set. Generate one with: openssl rand -base64 32");
    }

    if (oauthProperties.getIssuer().startsWith("http://")
        && !oauthProperties.getIssuer().contains("localhost")) {
      // Not fatal — someone may terminate TLS in front and be reached over https anyway —
      // but a non-local http issuer in a discovery document is worth saying out loud.
      log.warn(
          "oauth.issuer is {} — clients will be told to use plain HTTP. MCP clients"
              + " generally refuse a non-https remote server, and the bearer token would"
              + " travel unencrypted.",
          oauthProperties.getIssuer());
    }

    log.info("mcp-core ready: issuer={}", oauthProperties.getIssuer());
  }
}
