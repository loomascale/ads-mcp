package com.loomascale.mcp.defaults;

import com.loomascale.mcp.spi.McpAlertSink;
import lombok.extern.slf4j.Slf4j;

// Default alert destination. WARN rather than INFO: these are raised deliberately by a
// tool that has something an operator should see, such as a spend cap being hit.
@Slf4j
public class LoggingAlertSink implements McpAlertSink {

  @Override
  public void alert(String message) {
    log.warn("MCP alert: {}", message);
  }
}
