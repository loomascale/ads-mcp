package com.loomascale.mcp.spi;

// Notified after every tools/call, whatever the outcome. Invoked from the protocol
// layer's finally block so no exit path can skip it.
//
// Implementations are called on the request thread and must not block: the protocol layer
// does not wrap them in @Async, because whether that is wanted depends on where the record
// is going. A hosted deployment may republish onto an async event bus; the default logs.
public interface McpToolCallObserver {

  void onToolCall(ToolCallRecord record);
}
