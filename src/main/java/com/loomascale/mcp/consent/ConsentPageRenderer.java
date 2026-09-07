package com.loomascale.mcp.consent;

import com.loomascale.mcp.oauth.OAuthAuthorizationService.ConsentInfo;

// Renders the consent screen.
//
// An interface so a deployment can restyle it without replacing the whole flow, and so the
// default can stay a plain string template. Deliberately NOT a Spring view technology:
// contributing a ViewResolver from a library is a reliable way to break the host
// application's own view rendering, and this page has four dynamic values.
public interface ConsentPageRenderer {

  String renderHtml(ConsentInfo info, String requestId, String errorMessage);
}
