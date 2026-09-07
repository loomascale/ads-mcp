package com.loomascale.mcp.audit;

import java.time.Instant;

// One recorded write attempt, successful or not.
public record WriteAuditEntry(
    String id,
    String userId,
    String tool,
    WriteKind kind,
    // Arguments as JSON. Written for support and incident review, so it is stored as
    // given: this is the only record of what was actually asked for.
    String argsJson,
    // The platform object the write touched, when there is one. Named object_id rather
    // than anything platform-specific, because both platforms' ids land in this column.
    String objectId,
    boolean success,
    String error,
    Instant createdAt) {}
