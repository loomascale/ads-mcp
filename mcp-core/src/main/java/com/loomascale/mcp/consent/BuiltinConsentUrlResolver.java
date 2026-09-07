package com.loomascale.mcp.consent;

// Sends the browser to the consent page this library serves itself.
//
// `lang` is ignored: the bundled page is English-only. It is still carried through the
// authorization request so that a deployment which replaces this resolver can honour it.
public class BuiltinConsentUrlResolver implements ConsentUrlResolver {

  private final String issuer;
  private final String path;

  public BuiltinConsentUrlResolver(String issuer, String path) {
    this.issuer = issuer;
    this.path = path;
  }

  @Override
  public String consentUrl(String requestId, String lang) {
    return issuer + path + "?request_id=" + requestId;
  }
}
