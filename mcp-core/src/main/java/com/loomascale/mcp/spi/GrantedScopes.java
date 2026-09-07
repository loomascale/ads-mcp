package com.loomascale.mcp.spi;

// A connection stores the permissions the provider actually granted as one comma string.
// Reading it in more than one place invites two slightly different parsers, so both the
// auth gate and the ad-account reads share this one. It also parses a raw Google token
// response, whose `scope` is space-separated rather than comma-separated.
//
// Only the parsing lives here. The scope NAMES are platform vocabulary and belong to each
// platform's AdsPlatformDescriptor, so that this class — and the gate that uses it — can
// be shared by a server that knows about only one platform, or a third one.
public final class GrantedScopes {

  private GrantedScopes() {}

  // A blank scope string means "unknown", not "none": connections made before scopes
  // were persisted must keep working, so callers treat blank as permissive.
  public static boolean isKnown(String scopes) {
    return scopes != null && !scopes.isBlank();
  }

  // Splits on commas and whitespace alike, so this reads both the stored comma string
  // and a raw Google token response's space-separated `scope`.
  public static boolean has(String scopes, String scope) {
    if (!isKnown(scopes)) {
      return false;
    }
    for (String granted : scopes.split("[,\\s]+")) {
      if (granted.trim().equals(scope)) {
        return true;
      }
    }
    return false;
  }
}
