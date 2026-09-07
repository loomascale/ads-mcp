package com.loomascale.mcp.defaults;

import com.loomascale.mcp.spi.McpToolCallObserver;
import com.loomascale.mcp.spi.ToolCallRecord;
import lombok.extern.slf4j.Slf4j;

// Default observer: one line per tool call, which is the audit trail a self-hoster gets
// for free. Argument NAMES only — the record carries no values, because arguments hold
// model-supplied ad copy, URLs and account ids.
@Slf4j
public class LoggingToolCallObserver implements McpToolCallObserver {

  @Override
  public void onToolCall(ToolCallRecord record) {
    log.info(
        "MCP tool={} outcome={} durationMs={} user={} client={} args={}{}",
        record.toolName(),
        record.outcome(),
        record.durationMs(),
        record.userId(),
        record.clientId(),
        record.argFields(),
        record.failureMessage() == null ? "" : " failure=" + record.failureMessage());
  }
}
