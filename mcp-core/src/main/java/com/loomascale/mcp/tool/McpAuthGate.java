package com.loomascale.mcp.tool;

import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionState;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsPlatformDescriptor;
import com.loomascale.mcp.spi.GrantedScopes;
import com.loomascale.mcp.spi.ProductBranding;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

// Shared preconditions for every ads tool: a healthy connection on the tool's platform,
// with the scopes the tool needs. Failures throw McpToolException so the model sees an
// actionable message rather than a protocol error.
//
// Written once and parameterised by AdsPlatformDescriptor rather than duplicated per
// platform. This is a security control, and two copies of a security control drift.
// Billing is not checked here — QuotaPolicy enforces any allowance centrally in
// McpProtocolService, so paid and free users share these gates.
@Slf4j
public class McpAuthGate {

  private final AdsConnectionStore connectionStore;
  private final ProductBranding branding;
  private final Map<String, AdsPlatformDescriptor> descriptors;

  public McpAuthGate(
      AdsConnectionStore connectionStore,
      ProductBranding branding,
      List<AdsPlatformDescriptor> descriptors) {
    this.connectionStore = connectionStore;
    this.branding = branding;
    this.descriptors =
        descriptors.stream()
            .collect(Collectors.toMap(AdsPlatformDescriptor::platformKey, Function.identity()));
  }

  public AdsConnection requireConnection(String userId, String platformKey) {
    AdsPlatformDescriptor platform = descriptor(platformKey);
    AdsConnection connection =
        connectionStore
            .find(userId, platformKey)
            .orElseThrow(
                () ->
                    new McpToolException(
                        "No "
                            + platform.displayName()
                            + " account is connected. Connect one at "
                            + branding.connectionsUrl()));

    if (connection.state() == AdsConnectionState.NO_AD_ACCOUNT) {
      throw new McpToolException(
          platform.noAdAccountMessage().orElseGet(() -> expiredMessage(platform)));
    }
    if (connection.state() != AdsConnectionState.CONNECTED) {
      throw new McpToolException(expiredMessage(platform));
    }
    return connection;
  }

  // Write tools additionally need the platform's manage permission on the token. Both
  // platforms let the user deselect it on the consent screen, so a connection can be
  // healthy and still unable to write; without this check the write fails later with an
  // opaque API error.
  public AdsConnection requireWriteConnection(String userId, String platformKey) {
    AdsPlatformDescriptor platform = descriptor(platformKey);
    return requireScope(
        userId,
        platformKey,
        platform.writeScope(),
        branding.productName()
            + " can read your ad data but not change it — the "
            + platform.displayName()
            + " connection is missing the manage-campaigns permission. Reconnect at "
            + branding.connectionsUrl()
            + " and approve it.");
  }

  public AdsConnection requireScope(
      String userId, String platformKey, String scope, String message) {
    AdsConnection connection = requireConnection(userId, platformKey);
    String scopes = connection.grantedScopes();
    if (GrantedScopes.isKnown(scopes) && !GrantedScopes.has(scopes, scope)) {
      log.debug("{} connection {} lacks {}: {}", platformKey, connection.id(), scope, scopes);
      throw new McpToolException(message);
    }
    return connection;
  }

  private String expiredMessage(AdsPlatformDescriptor platform) {
    return "Your "
        + platform.displayName()
        + " connection has expired. Reconnect at "
        + branding.connectionsUrl();
  }

  private AdsPlatformDescriptor descriptor(String platformKey) {
    AdsPlatformDescriptor platform = descriptors.get(platformKey);
    if (platform == null) {
      throw new IllegalStateException("No AdsPlatformDescriptor registered for " + platformKey);
    }
    return platform;
  }
}
