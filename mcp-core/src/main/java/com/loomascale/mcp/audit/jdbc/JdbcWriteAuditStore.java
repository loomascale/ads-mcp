package com.loomascale.mcp.audit.jdbc;

import com.loomascale.mcp.audit.WriteAuditEntry;
import com.loomascale.mcp.audit.WriteAuditStore;
import com.loomascale.mcp.audit.WriteKind;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcWriteAuditStore implements WriteAuditStore {

  private final JdbcTemplate jdbc;

  public JdbcWriteAuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(WriteAuditEntry entry) {
    jdbc.update(
        "insert into mcp_write_audit (id, user_id, tool, kind, args_json, object_id, success,"
            + " error, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        entry.id(),
        entry.userId(),
        entry.tool(),
        entry.kind().name(),
        entry.argsJson(),
        entry.objectId(),
        entry.success(),
        entry.error(),
        Timestamp.from(entry.createdAt() == null ? Instant.now() : entry.createdAt()));
  }

  // Both predicates matter. `kind = CREATE` is what replaced matching on tool names, and
  // `success = true` is what stops a failed create from making an object look ours — the
  // object id in a failed row may not even exist.
  @Override
  public boolean createdHere(String userId, String objectId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from mcp_write_audit where user_id = ? and object_id = ?"
                + " and kind = ? and success = true",
            Integer.class,
            userId,
            objectId,
            WriteKind.CREATE.name());
    return count != null && count > 0;
  }

  @Override
  public long countSuccessSince(String userId, String tool, Instant since) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from mcp_write_audit where user_id = ? and tool = ?"
                + " and success = true and created_at >= ?",
            Long.class,
            userId,
            tool,
            Timestamp.from(since));
    return count == null ? 0L : count;
  }
}
