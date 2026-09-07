package com.loomascale.mcp.spi;

// How many tool calls a user may make. Extracted as an SPI because metering is a
// commercial concern, not an MCP one: a self-hosted server talking to its owner's own
// ad accounts has nobody to bill, while a hosted service enforces a plan allowance.
//
// The protocol layer calls requireRemaining() before dispatch and record() only after a
// successful, metered call, so a failed tool never costs the user a request.
public interface QuotaPolicy {

  // Throws McpQuotaExceededException when the caller has nothing left. The message is
  // shown to the model, so it must say what ran out and how to get more.
  void requireRemaining(String userId);

  // Called after a successful metered tool call.
  void record(String userId, String tool);

  // Current balance, for tools and dashboards that report remaining allowance.
  QuotaUsage usage(String userId);
}
