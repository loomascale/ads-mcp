package com.loomascale.mcp.audit;

import com.loomascale.mcp.spi.McpAlertSink;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Records every write-tool outcome, and answers the two questions that gate further
// writes: did this server create that object, and how many of this tool has this user
// already run today.
//
// The activation allowlist is the reason this is not merely logging. Activating a campaign
// starts real spend, and the budget check only happened for campaigns created through this
// server — so activating one created elsewhere would bypass the operator's cap entirely.
@Slf4j
@RequiredArgsConstructor
public class McpWriteAuditService {

  private final WriteAuditStore store;
  private final McpAlertSink alertSink;

  public void record(
      String userId,
      String tool,
      WriteKind kind,
      String argsJson,
      String objectId,
      boolean success,
      String error) {
    store.record(
        new WriteAuditEntry(
            UUID.randomUUID().toString(),
            userId,
            tool,
            kind,
            argsJson,
            objectId,
            success,
            error,
            Instant.now()));
    log.debug("Recorded {} {} success={} object={}", kind, tool, success, objectId);
  }

  public boolean wasCreatedByUs(String userId, String objectId) {
    return store.createdHere(userId, objectId);
  }

  public long countToday(String userId, String tool) {
    return store.countSuccessSince(userId, tool, Instant.now().minus(Duration.ofHours(24)));
  }

  // Raised by a tool that has something an operator should see. Routed through the SPI so
  // this class carries no notification transport of its own.
  public void alert(String message) {
    alertSink.alert(message);
  }
}
