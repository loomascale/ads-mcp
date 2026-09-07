package com.loomascale.mcp.tool;

// Thrown by tools/gates to signal a domain failure. The protocol layer turns it
// into a tool result with isError=true (message is safe to show the user/model).
public class McpToolException extends RuntimeException {
  public McpToolException(String message) {
    super(message);
  }
}
