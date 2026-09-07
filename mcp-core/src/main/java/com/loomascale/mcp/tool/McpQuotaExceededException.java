package com.loomascale.mcp.tool;

// Free-tier monthly limit reached. A McpToolException so the protocol layer keeps
// turning it into an isError=true tool result exactly as before; the distinct type
// only exists so the admin notification can tell "hit the paywall" apart from
// "Meta rejected the call".
public class McpQuotaExceededException extends McpToolException {
  public McpQuotaExceededException(String message) {
    super(message);
  }
}
