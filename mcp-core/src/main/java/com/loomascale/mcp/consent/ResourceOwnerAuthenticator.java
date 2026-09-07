package com.loomascale.mcp.consent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

// Who is approving the authorization request.
//
// This is the one piece of the OAuth flow a library cannot decide for you: the resource
// owner must be authenticated by whatever already knows who your users are. A hosted
// service checks its own session or login token; a single-operator self-host has exactly
// one user and only needs to prove it is them.
//
// Returning empty means "not signed in" — the consent endpoint answers 401 and the
// operator is expected to authenticate and retry, never that consent is silently denied.
public interface ResourceOwnerAuthenticator {

  Optional<String> authenticate(HttpServletRequest request);
}
