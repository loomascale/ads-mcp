package com.loomascale.mcp.spi;

// The product name and the user-facing links the MCP layer puts into tool
// descriptions and error messages. Extracted as an SPI so the MCP server can be
// hosted under any brand: a self-host supplies a configured name and its own URLs, while a
// hosted service supplies its product name and locale-aware dashboard links.
//
// Tool text must never hardcode a brand or a host. A self-hoster who reads
// "Reconnect at https://ai.loomascale.com/dashboard/connections" has been handed an
// instruction they cannot act on.
public interface ProductBranding {

  // Display name used in user-facing error messages, e.g. "Acme Ads".
  String productName();

  // Where the user manages ad-platform connections. Shown when a tool needs a
  // connection that is missing, expired, or under-scoped.
  String connectionsUrl();

  // The OAuth consent screen for one pending authorization request.
  String consentUrl(String requestId);

  // Server name reported in the MCP `initialize` handshake. Identifies the server to
  // the client, so it is a stable slug rather than a display name.
  String serverName();
}
