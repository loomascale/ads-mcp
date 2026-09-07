package com.loomascale.googleads.mcp.connect;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Google OAuth client credentials, plus the scopes to request.
//
// Bound here rather than mirrored into application.yml, so that "required" is a fact the
// compiler and the IDE can both see, and so no placeholder default can turn into a
// committed credential. Obtaining each value is documented in .env.example.
@Validated
@ConfigurationProperties(prefix = "google-ads")
public record GoogleOAuthConfig(
    @NotBlank(message =
            "set GOOGLE_ADS_CLIENT_ID — Google Cloud console, APIs & Services, Credentials,"
                + " OAuth client ID")
        String clientId,
    @NotBlank(message = "set GOOGLE_ADS_CLIENT_SECRET — the secret for that same OAuth client")
        String clientSecret,
    @NotBlank(message =
            "set GOOGLE_ADS_DEVELOPER_TOKEN — apply in your Google Ads manager account under"
                + " Tools & Settings, API Center. Approval takes days, so start there.")
        String developerToken,
    String apiVersion,
    String scopes,
    // Where Google sends the browser back. Must exactly match a redirect URI registered on
    // the OAuth client, or Google refuses the request with redirect_uri_mismatch.
    String redirectUri) {

  // openid and email ride alongside adwords so that one consent both identifies the
  // operator and grants Ads access. adwords is separately deselectable on Google's consent
  // screen, which is why a connection can be healthy and still unable to do anything.
  public static final String DEFAULT_SCOPES =
      "openid email https://www.googleapis.com/auth/adwords";

  public GoogleOAuthConfig {
    if (scopes == null || scopes.isBlank()) {
      scopes = DEFAULT_SCOPES;
    }
  }

  // Google's token endpoint wants space-separated scopes; a comma-separated value is the
  // more natural thing to write in an env var, so both are accepted.
  public String scopesForRequest() {
    return scopes.replace(',', ' ');
  }
}
