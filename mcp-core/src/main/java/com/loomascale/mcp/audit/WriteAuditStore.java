package com.loomascale.mcp.audit;

import java.time.Instant;

// Persistence for the write audit log.
public interface WriteAuditStore {

  void record(WriteAuditEntry entry);

  // Whether this server created the object, and the creation succeeded. This is the
  // activation allowlist: a campaign nobody here created must not be activatable through
  // a tool call, because its budget was never checked against the operator's cap.
  boolean createdHere(String userId, String objectId);

  // Successful calls of one tool since a moment, for per-tool daily limits.
  long countSuccessSince(String userId, String tool, Instant since);
}
