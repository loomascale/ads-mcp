package com.loomascale.mcp.tool;

import com.loomascale.mcp.protocol.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

// One MCP tool. Implementations are @Component beans auto-collected by
// McpToolRegistry (mirrors SocialProviderRegistry). The handler receives the
// already-authenticated userId; it must never trust identity from args.
public interface AdsTool {

  String name();

  String description();

  ObjectNode inputSchema();

  // JSON Schema for the structuredContent returned on success. Declaring it means
  // every non-error result must validate against it, so only fields that are always
  // written belong in `required`.
  ObjectNode outputSchema();

  ToolAnnotations annotations();

  // true when the tool mutates ad state and therefore requires the ads.write scope.
  boolean requiresWriteScope();

  // false for tools that must neither be blocked by nor count against the free-tier
  // quota — checking the remaining balance has to keep working once it hits zero,
  // and asking must not itself cost a request.
  default boolean countsAgainstQuota() {
    return true;
  }

  ToolResult execute(String userId, JsonNode args);
}
