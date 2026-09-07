package com.loomascale.mcp.spi;

// Where a tool sends an operator-facing warning about something that already happened —
// a budget cap hit, a write that partially succeeded. Separate from McpToolCallObserver:
// that one sees every call, this one is raised deliberately by a tool that has something
// worth a human's attention.
//
// An SPI because the destination is deployment-specific: a hosted service may post to an
// operator chat channel, a self-hoster most likely just wants it in the log.
public interface McpAlertSink {

  void alert(String message);
}
