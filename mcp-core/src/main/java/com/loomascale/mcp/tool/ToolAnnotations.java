package com.loomascale.mcp.tool;

// MCP tool behavior hints surfaced in tools/list. Clients use these to decide
// when to ask the user for confirmation, so they must be accurate:
// readOnly=true only for pure reads; destructive=true for spend-affecting writes.
public record ToolAnnotations(
    boolean readOnlyHint, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {

  public static ToolAnnotations readOnly() {
    // openWorld=true: every read tool queries an external ad platform (Meta Graph API /
    // Google Ads API) whose state this server does not control, which is the MCP
    // definition of an open-world interaction regardless of read-only-ness.
    return new ToolAnnotations(true, false, true, true);
  }

  // Read of this server's own state — no external ad platform is involved, so unlike
  // readOnly() this one is closed-world.
  public static ToolAnnotations localReadOnly() {
    return new ToolAnnotations(true, false, true, false);
  }

  public static ToolAnnotations write(boolean destructive, boolean idempotent) {
    return new ToolAnnotations(false, destructive, idempotent, true);
  }
}
