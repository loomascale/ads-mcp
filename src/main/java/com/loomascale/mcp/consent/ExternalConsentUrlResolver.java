package com.loomascale.mcp.consent;

// Sends the browser to a consent page served by something else — a deployment's own front
// end. The template may use {request_id} and {lang}.
public class ExternalConsentUrlResolver implements ConsentUrlResolver {

  private final String template;

  public ExternalConsentUrlResolver(String template) {
    this.template = template;
  }

  @Override
  public String consentUrl(String requestId, String lang) {
    return template
        .replace("{request_id}", requestId)
        .replace("{lang}", lang == null ? "" : lang);
  }
}
