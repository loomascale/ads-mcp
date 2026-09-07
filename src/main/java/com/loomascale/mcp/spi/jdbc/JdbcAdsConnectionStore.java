package com.loomascale.mcp.spi.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.security.TokenCipher;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionState;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsTarget;
import com.loomascale.mcp.spi.AdsTokenRefresher;
import com.loomascale.mcp.tool.McpToolException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

// The built-in connection store: one row per (user, platform) in mcp_ads_connections,
// tokens encrypted with TokenCipher.
//
// This is what a self-host gets for free, so that supplying credentials does not require
// implementing an interface first. A deployment that already stores connections elsewhere
// replaces the bean.
//
// Token refresh is delegated to an AdsTokenRefresher contributed per platform: the
// refresh-if-near-expiry, persist, and expire-only-on-a-real-refusal logic is generic and
// lives here, while the actual HTTP call to Google or Meta does not.
@Slf4j
public class JdbcAdsConnectionStore implements AdsConnectionStore {

  // Renew this far ahead of expiry so a call that takes a few seconds cannot begin with a
  // valid token and end with an expired one.
  private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

  private static final String COLUMNS =
      "id, user_id, platform_key, state, access_token_enc, refresh_token_enc, token_expires_at,"
          + " granted_scopes, selected_target_id, targets_json, max_daily_budget_minor,"
          + " max_account_budget_minor, default_page_id";

  private final JdbcTemplate jdbc;
  private final TokenCipher cipher;
  private final ObjectMapper objectMapper;
  private final Map<String, AdsTokenRefresher> refreshers;

  public JdbcAdsConnectionStore(
      JdbcTemplate jdbc,
      TokenCipher cipher,
      ObjectMapper objectMapper,
      List<AdsTokenRefresher> refreshers) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.objectMapper = objectMapper;
    this.refreshers =
        refreshers.stream()
            .collect(Collectors.toMap(AdsTokenRefresher::platformKey, Function.identity()));
  }

  @Override
  public Optional<AdsConnection> find(String userId, String platformKey) {
    return jdbc
        .query(
            "select "
                + COLUMNS
                + " from mcp_ads_connections where user_id = ? and platform_key = ?",
            (ResultSet rs, int rowNum) -> toValue(rs),
            userId,
            platformKey)
        .stream()
        .findFirst();
  }

  @Override
  public String freshAccessToken(AdsConnection connection) {
    Row row = row(connection.id());
    if (row.expiresAt != null && row.expiresAt.isAfter(Instant.now().plus(REFRESH_MARGIN))) {
      return cipher.decrypt(row.accessTokenEnc);
    }

    AdsTokenRefresher refresher = refreshers.get(connection.platformKey());
    if (refresher == null) {
      // No refresher for this platform: the stored token is all there is. Hand it over
      // rather than failing — a long-lived token may still be perfectly valid.
      log.debug("No token refresher for {}; using the stored token", connection.platformKey());
      return cipher.decrypt(row.accessTokenEnc);
    }

    try {
      // decrypt(null) is null, so a connection with no stored refresh token simply passes
      // null through and the refresher decides whether it can work without one.
      AdsTokenRefresher.RefreshedTokens refreshed =
          refresher.refresh(cipher.decrypt(row.refreshTokenEnc));
      applyRefreshed(connection.id(), refreshed);
      return refreshed.accessToken();
    } catch (AdsTokenRefresher.GrantRevokedException e) {
      // The platform says the grant is gone: only reconnecting can fix it.
      markExpired(connection.id());
      throw new McpToolException(
          "The connection to "
              + connection.platformKey()
              + " has expired and needs to be"
              + " reconnected.");
    } catch (RuntimeException e) {
      // Anything else is treated as transient and must NOT expire the connection: a token
      // endpoint that is merely down would otherwise disconnect every user permanently.
      log.warn("Token refresh failed for {}: {}", connection.id(), e.getMessage());
      throw new McpToolException(
          "Could not renew the connection to "
              + connection.platformKey()
              + " just now. Try again in a moment.");
    }
  }

  @Override
  public List<AdsTarget> targets(AdsConnection connection) {
    String json = row(connection.id()).targetsJson;
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<StoredTarget> stored =
          objectMapper.readValue(json, new TypeReference<List<StoredTarget>>() {});
      List<AdsTarget> targets = new ArrayList<>();
      for (StoredTarget t : stored) {
        targets.add(
            new AdsTarget(
                t.id(),
                t.name(),
                t.currency(),
                t.loginHintEnc() == null ? null : cipher.decrypt(t.loginHintEnc())));
      }
      return targets;
    } catch (Exception e) {
      // An unreadable snapshot yields no targets rather than failing the tool: the caller
      // then reports "no account selected", which is actionable.
      log.warn("Unreadable target snapshot on {}: {}", connection.id(), e.getMessage());
      return List.of();
    }
  }

  @Override
  public void markExpired(String connectionId) {
    jdbc.update(
        "update mcp_ads_connections set state = ? where id = ?",
        AdsConnectionState.EXPIRED.name(),
        connectionId);
    log.warn("Marked connection {} EXPIRED", connectionId);
  }

  @Override
  public Optional<String> storedCurrency(AdsConnection connection, String targetId) {
    return targets(connection).stream()
        .filter(t -> targetId.equals(t.id()))
        .map(AdsTarget::currency)
        .filter(c -> c != null && !c.isBlank())
        .findFirst();
  }

  @Override
  public void rememberCurrency(AdsConnection connection, String targetId, String currency) {
    List<AdsTarget> updated =
        targets(connection).stream()
            .map(
                t ->
                    targetId.equals(t.id())
                        ? new AdsTarget(t.id(), t.name(), currency, t.loginHint())
                        : t)
            .toList();
    writeTargets(connection.id(), updated);
  }

  // Writes the snapshot back, re-encrypting each login hint.
  private void writeTargets(String connectionId, List<AdsTarget> targets) {
    List<StoredTarget> stored =
        targets.stream()
            .map(
                t ->
                    new StoredTarget(
                        t.id(),
                        t.name(),
                        t.currency(),
                        t.loginHint() == null ? null : cipher.encrypt(t.loginHint())))
            .toList();
    try {
      jdbc.update(
          "update mcp_ads_connections set targets_json = ? where id = ?",
          objectMapper.writeValueAsString(stored),
          connectionId);
    } catch (Exception e) {
      throw new IllegalStateException("Could not write the target snapshot", e);
    }
  }

  private void applyRefreshed(String connectionId, AdsTokenRefresher.RefreshedTokens refreshed) {
    jdbc.update(
        "update mcp_ads_connections set access_token_enc = ?, refresh_token_enc = coalesce(?,"
            + " refresh_token_enc), token_expires_at = ?, state = ? where id = ?",
        cipher.encrypt(refreshed.accessToken()),
        refreshed.refreshToken() == null ? null : cipher.encrypt(refreshed.refreshToken()),
        refreshed.expiresAt() == null ? null : Timestamp.from(refreshed.expiresAt()),
        AdsConnectionState.CONNECTED.name(),
        connectionId);
  }

  // Re-read rather than trusting the value handed in: a tool call can span seconds, and a
  // refresh in between must be visible to the next read.
  private Row row(String connectionId) {
    return jdbc
        .query(
            "select " + COLUMNS + " from mcp_ads_connections where id = ?",
            (ResultSet rs, int rowNum) -> toRow(rs),
            connectionId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new McpToolException("That ad account connection no longer exists."));
  }

  private record StoredTarget(String id, String name, String currency, String loginHintEnc) {}

  private static class Row {
    String accessTokenEnc;
    String refreshTokenEnc;
    Instant expiresAt;
    String targetsJson;
  }

  private static Row toRow(ResultSet rs) throws SQLException {
    Row row = new Row();
    row.accessTokenEnc = rs.getString("access_token_enc");
    row.refreshTokenEnc = rs.getString("refresh_token_enc");
    Timestamp expires = rs.getTimestamp("token_expires_at");
    row.expiresAt = expires == null ? null : expires.toInstant();
    row.targetsJson = rs.getString("targets_json");
    return row;
  }

  private static AdsConnection toValue(ResultSet rs) throws SQLException {
    return new AdsConnection(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("platform_key"),
        AdsConnectionState.valueOf(rs.getString("state")),
        rs.getString("granted_scopes"),
        rs.getString("selected_target_id"),
        (Long) rs.getObject("max_daily_budget_minor"),
        (Long) rs.getObject("max_account_budget_minor"),
        rs.getString("default_page_id"));
  }
}
