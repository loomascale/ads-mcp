package com.loomascale.mcp.oauth.jdbc;

import com.loomascale.mcp.oauth.OAuthRefreshToken;
import com.loomascale.mcp.oauth.RefreshTokenStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcRefreshTokenStore implements RefreshTokenStore {

  private static final String COLUMNS =
      "id, token_hash, family_id, client_id, user_id, scope, expires_at, rotated, revoked,"
          + " created_at";

  private final JdbcTemplate jdbc;

  public JdbcRefreshTokenStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<OAuthRefreshToken> MAPPER =
      (ResultSet rs, int rowNum) ->
          new OAuthRefreshToken(
              rs.getString("id"),
              rs.getString("token_hash"),
              rs.getString("family_id"),
              rs.getString("client_id"),
              rs.getString("user_id"),
              rs.getString("scope"),
              instant(rs, "expires_at"),
              rs.getBoolean("rotated"),
              rs.getBoolean("revoked"),
              instant(rs, "created_at"));

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  @Override
  public Optional<OAuthRefreshToken> findByTokenHash(String tokenHash) {
    return jdbc.query(
            "select " + COLUMNS + " from oauth_refresh_tokens where token_hash = ?",
            MAPPER,
            tokenHash)
        .stream()
        .findFirst();
  }

  @Override
  public void save(OAuthRefreshToken token) {
    jdbc.update(
        "insert into oauth_refresh_tokens (" + COLUMNS + ")"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        token.id(),
        token.tokenHash(),
        token.familyId(),
        token.clientId(),
        token.userId(),
        token.scope(),
        token.expiresAt() == null ? null : Timestamp.from(token.expiresAt()),
        token.rotated(),
        token.revoked(),
        Timestamp.from(token.createdAt() == null ? Instant.now() : token.createdAt()));
  }

  @Override
  public void markRotated(String id) {
    jdbc.update("update oauth_refresh_tokens set rotated = true where id = ?", id);
  }

  @Override
  public int revokeFamily(String familyId) {
    return jdbc.update(
        "update oauth_refresh_tokens set revoked = true where family_id = ?", familyId);
  }

  @Override
  public boolean hasLiveToken(String userId, Instant now) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from oauth_refresh_tokens"
                + " where user_id = ? and revoked = false and expires_at > ?",
            Integer.class,
            userId,
            Timestamp.from(now));
    return count != null && count > 0;
  }
}
