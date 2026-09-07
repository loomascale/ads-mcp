package com.loomascale.mcp.autoconfigure;

import com.loomascale.mcp.consent.BuiltinConsentUrlResolver;
import com.loomascale.mcp.consent.ConsentUrlResolver;
import com.loomascale.mcp.consent.ExternalConsentUrlResolver;
import com.loomascale.mcp.defaults.ConfiguredProductBranding;
import com.loomascale.mcp.spi.ProductBranding;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Everything under `mcp.*`.
//
// Note what is NOT here: no secret has a default, and no secret is named in the shipped
// application.yml at all. Secrets are bound where "required" can be enforced and seen —
// see OAuthProperties for the signing key. A placeholder default in YAML is how the
// codebase this was extracted from committed live credentials.
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

  private final Branding branding = new Branding();
  private final Quota quota = new Quota();
  private final Consent consent = new Consent();

  // The public origin this server is reached at. Used to build the consent URL that the
  // bundled page is served from.
  @Value("${oauth.issuer:http://localhost:8080}")
  private String issuer;

  @Getter
  @Setter
  public static class Branding {
    // Reads correctly inside a sentence a model relays to a user: "only campaigns created
    // through this server can be activated". A blank name would not.
    private String productName = "this server";
    // Reported in the MCP initialize handshake, so a stable slug rather than a title.
    private String serverName = "ads-mcp";
    // Where a user manages ad-platform connections. Shown whenever a tool needs a
    // connection that is missing, expired or under-scoped.
    private String connectionsUrl = "";
  }

  @Getter
  @Setter
  public static class Quota {
    // Runaway-loop guard, not billing. A model stuck in a loop can otherwise issue
    // hundreds of writes against a live ad account in a minute.
    private int turnWindowSeconds = 119;
    private int maxCallsPerTurn = 40;
  }

  @Getter
  @Setter
  public static class Consent {
    // "builtin" serves the bundled page; "external" redirects to `url` instead.
    private String mode = "builtin";
    private String path = "/oauth/consent";
    // Template for external mode; may use {request_id} and {lang}.
    private String url = "";
    // Single-operator mode: the password that authorizes consent, and the user id every
    // authorization is then attributed to. No default — a blank password means the
    // bundled consent flow refuses to start rather than accepting anyone.
    private String operatorPassword = "";
    private String userId = "self";
  }

  public ProductBranding toBranding() {
    String connections =
        branding.getConnectionsUrl().isBlank()
            ? issuer + "/connections"
            : branding.getConnectionsUrl();
    return new ConfiguredProductBranding(
        branding.getProductName(),
        branding.getServerName(),
        connections,
        issuer + consent.getPath() + "?request_id={request_id}");
  }

  public ConsentUrlResolver toConsentUrlResolver() {
    if ("external".equalsIgnoreCase(consent.getMode())) {
      if (consent.getUrl().isBlank()) {
        throw new IllegalStateException(
            "mcp.consent.mode is 'external' but mcp.consent.url is not set, so there is"
                + " nowhere to send the user to approve access. Set mcp.consent.url, or"
                + " switch mcp.consent.mode to 'builtin' to use the bundled page.");
      }
      return new ExternalConsentUrlResolver(consent.getUrl());
    }
    return new BuiltinConsentUrlResolver(issuer, consent.getPath());
  }
}
