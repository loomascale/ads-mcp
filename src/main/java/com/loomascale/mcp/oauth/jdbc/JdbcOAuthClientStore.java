package com.loomascale.mcp.oauth.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.oauth.OAuthClient;
import com.loomascale.mcp.oauth.OAuthClientStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcOAuthClientStore implements OAuthClientStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final RowMapper<OAuthClient> mapper;

  public JdbcOAuthClientStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.mapper = (ResultSet rs, int rowNum) -> map(rs);
  }

  private OAuthClient map(ResultSet rs) throws SQLException {
    return new OAuthClient(
        rs.getString("id"),
        rs.getString("client_id"),
        rs.getString("client_name"),
        readUris(rs.getString("redirect_uris")),
        rs.getString("token_endpoint_auth_method"),
        rs.getString("scope"),
        instant(rs, "created_at"));
  }

  // The column holds a JSON array. Kept as JSON rather than a delimited string so that a
  // deployment adopting this library against an existing oauth_clients table keeps
  // working: its rows were written that way, and a redirect URI it cannot read is a
  // client that can no longer connect.
  private List<String> readUris(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(stored, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      // Never guess at a redirect URI: an unreadable row must fail closed, so the client
      // simply has no registered redirect and cannot complete a flow.
      return List.of();
    }
  }

  private String writeUris(List<String> uris) {
    try {
      return objectMapper.writeValueAsString(uris);
    } catch (Exception e) {
      throw new IllegalArgumentException("Could not serialize redirect_uris", e);
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  @Override
  public Optional<OAuthClient> findByClientId(String clientId) {
    return jdbc
        .query(
            "select id, client_id, client_name, redirect_uris, token_endpoint_auth_method,"
                + " scope, created_at from oauth_clients where client_id = ?",
            mapper,
            clientId)
        .stream()
        .findFirst();
  }

  @Override
  public void save(OAuthClient client) {
    jdbc.update(
        "insert into oauth_clients (id, client_id, client_name, redirect_uris,"
            + " token_endpoint_auth_method, scope, created_at) values (?, ?, ?, ?, ?, ?, ?)",
        client.id(),
        client.clientId(),
        client.clientName(),
        writeUris(client.redirectUris()),
        client.tokenEndpointAuthMethod(),
        client.scope(),
        Timestamp.from(client.createdAt() == null ? Instant.now() : client.createdAt()));
  }
}
