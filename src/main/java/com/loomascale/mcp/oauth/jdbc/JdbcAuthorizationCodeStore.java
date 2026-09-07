package com.loomascale.mcp.oauth.jdbc;

import com.loomascale.mcp.oauth.AuthorizationCodeStore;
import com.loomascale.mcp.oauth.OAuthAuthorizationCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcAuthorizationCodeStore implements AuthorizationCodeStore {

  private static final String COLUMNS =
      "id, code_hash, client_id, user_id, redirect_uri, scope, code_challenge,"
          + " code_challenge_method, resource, expires_at, used_at, created_at";

  private final JdbcTemplate jdbc;

  public JdbcAuthorizationCodeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<OAuthAuthorizationCode> MAPPER =
      (ResultSet rs, int rowNum) ->
          new OAuthAuthorizationCode(
              rs.getString("id"),
              rs.getString("code_hash"),
              rs.getString("client_id"),
              rs.getString("user_id"),
              rs.getString("redirect_uri"),
              rs.getString("scope"),
              rs.getString("code_challenge"),
              rs.getString("code_challenge_method"),
              rs.getString("resource"),
              instant(rs, "expires_at"),
              instant(rs, "used_at"),
              instant(rs, "created_at"));

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  @Override
  public Optional<OAuthAuthorizationCode> findByCodeHash(String codeHash) {
    return jdbc
        .query(
            "select " + COLUMNS + " from oauth_authorization_codes where code_hash = ?",
            MAPPER,
            codeHash)
        .stream()
        .findFirst();
  }

  @Override
  public void save(OAuthAuthorizationCode code) {
    jdbc.update(
        "insert into oauth_authorization_codes ("
            + COLUMNS
            + ")"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        code.id(),
        code.codeHash(),
        code.clientId(),
        code.userId(),
        code.redirectUri(),
        code.scope(),
        code.codeChallenge(),
        code.codeChallengeMethod(),
        code.resource(),
        timestamp(code.expiresAt()),
        timestamp(code.usedAt()),
        timestamp(code.createdAt() == null ? Instant.now() : code.createdAt()));
  }

  // The `used_at is null` predicate is what makes the code single-use under concurrency.
  // Two simultaneous redemptions both match the row, but only one UPDATE sees it unused,
  // so exactly one caller gets a row count of 1 and mints a token.
  @Override
  public boolean markUsed(String codeHash, Instant now) {
    return jdbc.update(
            "update oauth_authorization_codes set used_at = ?"
                + " where code_hash = ? and used_at is null",
            Timestamp.from(now),
            codeHash)
        == 1;
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
