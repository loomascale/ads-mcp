package com.loomascale.mcp.tool;

// How a tools/call request finished. Reported to the admin Telegram channel by
// McpEventListener; the wire response the model sees is unaffected by this value.
public enum McpCallOutcome {
  // Tool ran and returned isError=false. The only outcome that burns free-tier quota.
  OK,
  // Tool ran (or a gate rejected it) and returned a domain failure — no connection,
  // expired token, Meta/Google refused the call.
  TOOL_ERROR,
  // Write tool called with a token that never got the ads.write scope.
  SCOPE_DENIED,
  // Free-tier monthly limit already reached; the tool never executed.
  QUOTA_EXHAUSTED,
  // params.name matched no registered tool — surfaced as JSON-RPC -32602, not a tool result.
  UNKNOWN_TOOL;

  // Title-friendly form for the notification, e.g. QUOTA_EXHAUSTED -> "QUOTA EXHAUSTED".
  public String label() {
    return name().replace('_', ' ');
  }
}
