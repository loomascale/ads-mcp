package com.loomascale.mcp.spi;

import com.loomascale.mcp.tool.McpToolException;
import java.util.List;
import java.util.Optional;

// Where ad-platform credentials live, and the only place that knows how they are stored.
//
// Every operation that needs a secret or touches persistence is here rather than on
// AdsConnection, so the connection itself stays an inert value. That keeps token
// encryption, refresh and expiry entirely on one side of the boundary: a hosted service can
// back this with an encrypted database table, a self-host with environment variables, and no
// tool or ads service can tell the difference.
public interface AdsConnectionStore {

  Optional<AdsConnection> find(String userId, String platformKey);

  // A usable access token, refreshing first when the stored one is expired or near it.
  //
  // Throws McpToolException when the grant is dead and only reconnecting can fix it. A
  // transient failure of the platform's token endpoint must throw WITHOUT expiring the
  // connection — otherwise an outage at Google or Meta logs every user out permanently.
  String freshAccessToken(AdsConnection connection);

  // The ad accounts this connection can reach, with secrets already decrypted.
  List<AdsTarget> targets(AdsConnection connection);

  // Called when the platform itself reports the grant is gone. Not for permission errors:
  // a token that works but lacks a role on one account is not an expired connection, and
  // expiring it would send the user through a reconnect that changes nothing.
  void markExpired(String connectionId);

  // Currency is cached on the connection after the first lookup so that every money value
  // can be labelled without an extra API call per tool invocation.
  Optional<String> storedCurrency(AdsConnection connection, String targetId);

  void rememberCurrency(AdsConnection connection, String targetId, String currency);
}
