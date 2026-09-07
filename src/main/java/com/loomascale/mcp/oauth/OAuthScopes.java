package com.loomascale.mcp.oauth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// The two scopes this AS issues. ads.read for the read tools; ads.write for the
// guarded write tools (creation, budget, pause/resume, activation). Activation is
// gated server-side, not by a separate scope, to keep consent simple.
public final class OAuthScopes {

  public static final String ADS_READ = "ads.read";
  public static final String ADS_WRITE = "ads.write";

  public static final List<String> SUPPORTED = List.of(ADS_READ, ADS_WRITE);

  private OAuthScopes() {}

  // Requested scope narrowed to what we support. Unknown scopes are dropped.
  // Empty request defaults to read-only.
  public static String narrow(String requested) {
    if (requested == null || requested.isBlank()) {
      return ADS_READ;
    }
    Set<String> granted = new LinkedHashSet<>();
    for (String s : requested.trim().split("\\s+")) {
      if (SUPPORTED.contains(s)) {
        granted.add(s);
      }
    }
    if (granted.isEmpty()) {
      granted.add(ADS_READ);
    }
    return String.join(" ", granted);
  }
}
