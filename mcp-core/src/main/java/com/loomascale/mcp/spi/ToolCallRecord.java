package com.loomascale.mcp.spi;

import com.loomascale.mcp.tool.McpCallOutcome;
import java.time.Instant;
import java.util.List;

// One completed tools/call. `argFields` carries argument NAMES only, never values —
// arguments hold model-supplied ad copy, URLs and account ids, and this record travels to
// logs and notification channels.
public record ToolCallRecord(
    String userId,
    String clientId,
    String jti,
    String toolName,
    List<String> argFields,
    McpCallOutcome outcome,
    String failureMessage,
    long durationMs,
    Instant at) {}
