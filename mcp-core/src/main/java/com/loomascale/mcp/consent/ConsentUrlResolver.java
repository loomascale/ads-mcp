package com.loomascale.mcp.consent;

// Where to send the browser to approve one pending authorization request.
//
// Split from the authorization service because the consent screen is a UI decision: the
// bundled server-rendered page is enough to run, and a deployment with its own front end
// points this at that instead.
public interface ConsentUrlResolver {

  // `lang` is an opaque pass-through: it rides the authorization request so a deployment
  // with a localised front end can honour it. The bundled resolver ignores it.
  String consentUrl(String requestId, String lang);
}
