package com.loomascale.mcp.tool;

// Result of a tool call. `text` is the human/model-readable summary; `structured`
// is an optional object serialized into structuredContent. isError marks a domain
// failure (returned as an MCP tool error, NOT a JSON-RPC protocol error).
public record ToolResult(String text, Object structured, boolean isError) {

  public static ToolResult ok(String text, Object structured) {
    return new ToolResult(text, structured, false);
  }

  public static ToolResult error(String text) {
    return new ToolResult(text, null, true);
  }
}
