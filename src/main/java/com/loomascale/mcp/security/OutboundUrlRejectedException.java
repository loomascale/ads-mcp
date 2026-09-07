package com.loomascale.mcp.security;

// A URL this server refuses to fetch, as opposed to one it failed to fetch. The
// distinction drives the caller's behaviour: a rejection is permanent and must be
// reported to the user, while a failure may be retried or worked around.
public class OutboundUrlRejectedException extends RuntimeException {

  public OutboundUrlRejectedException(String message) {
    super(message);
  }
}
