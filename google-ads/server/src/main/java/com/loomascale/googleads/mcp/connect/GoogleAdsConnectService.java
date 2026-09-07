package com.loomascale.googleads.mcp.connect;

import com.loomascale.googleads.mcp.GoogleAdsPlatformDescriptor;
import com.loomascale.googleads.mcp.connect.GoogleAdsOAuthClient.GoogleToken;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionState;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.AdsTarget;
import com.loomascale.mcp.spi.GrantedScopes;
import com.loomascale.mcp.spi.NewAdsConnection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Turns a completed Google consent into a stored connection.
//
// The interesting part is that "it worked" has three distinct failure-ish outcomes, and
// conflating them produces advice the user cannot act on:
//
//   the adwords scope was declined  -> reconnect and tick the box; the Ads API was never
//                                      called, so we know nothing about their accounts
//   granted, but no ad accounts     -> reconnecting changes nothing. They need to create a
//                                      Google Ads account, after which the stored refresh
//                                      token picks it up with no second sign-in
//   granted, with accounts          -> connected
//
// The middle case is why AdsConnectionState has NO_AD_ACCOUNT at all.
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAdsConnectService {

  private final GoogleAdsOAuthClient oauthClient;
  private final GoogleAdsAccountDiscovery discovery;
  private final AdsConnectionStore connectionStore;
  private final GoogleOAuthConfig config;

  public String authorizationUrl(String state) {
    return oauthClient.buildAuthorizationUrl(config.redirectUri(), state);
  }

  public ConnectOutcome completeConnect(String userId, String code) {
    GoogleToken token = oauthClient.exchangeCode(code, config.redirectUri());
    boolean adsGranted =
        GrantedScopes.has(token.scope(), GoogleAdsPlatformDescriptor.ADWORDS_SCOPE);

    if (!adsGranted) {
      // Deliberately not stored. A connection that cannot call the Ads API is worse than
      // none: every tool would fail its scope gate, and the user would see "reconnect and
      // approve" against a row that looks healthy.
      log.debug("Google consent completed without the adwords scope; not storing");
      return ConnectOutcome.declined();
    }

    // Skipped when the scope was declined, above: asking the Ads API anyway answers 403
    // and would lose an identity the user did grant.
    List<AdsTarget> targets = discovery.discoverTargets(token.accessToken());
    AdsConnectionState state =
        targets.isEmpty() ? AdsConnectionState.NO_AD_ACCOUNT : AdsConnectionState.CONNECTED;

    AdsConnection stored =
        connectionStore.save(
            new NewAdsConnection(
                userId,
                AdsPlatforms.GOOGLE_ADS,
                state,
                token.accessToken(),
                token.refreshToken(),
                token.expiresAt(),
                token.scope(),
                targets,
                // Pre-select when there is exactly one, since there is nothing to choose
                // between. With several, the operator picks: guessing would silently point
                // every tool call at whichever account happened to be listed first.
                targets.size() == 1 ? targets.get(0).id() : null,
                null));

    log.info(
        "Google Ads connected for {}: {} manageable account(s), state {}",
        userId,
        targets.size(),
        state);
    return new ConnectOutcome(true, state, stored, targets);
  }

  public void selectAccount(String userId, String targetId) {
    AdsConnection connection =
        connectionStore
            .find(userId, AdsPlatforms.GOOGLE_ADS)
            .orElseThrow(() -> new IllegalStateException("No Google Ads connection to update"));
    boolean known =
        connectionStore.targets(connection).stream().anyMatch(t -> targetId.equals(t.id()));
    if (!known) {
      // Never store an unvalidated id: every later tool call would send it to Google and
      // fail with something opaque, a long way from this decision.
      throw new IllegalArgumentException("That account is not one of your connected accounts");
    }
    connectionStore.selectTarget(connection.id(), targetId);
    log.debug("Selected Google Ads account {} for {}", targetId, userId);
  }

  public record ConnectOutcome(
      boolean stored, AdsConnectionState state, AdsConnection connection, List<AdsTarget> targets) {

    static ConnectOutcome declined() {
      return new ConnectOutcome(false, null, null, List.of());
    }

    public boolean scopeDeclined() {
      return !stored;
    }

    public boolean needsGoogleAdsAccount() {
      return state == AdsConnectionState.NO_AD_ACCOUNT;
    }
  }
}
