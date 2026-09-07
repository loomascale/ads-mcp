package com.loomascale.googleads.mcp.connect;

import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.GoogleAdsApiException;
import com.loomascale.googleads.client.dto.GoogleAdAccountDto;
import com.loomascale.mcp.spi.AdsTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Works out which Google Ads accounts a freshly granted token can actually manage.
//
// Google's model needs flattening before it is useful: listAccessibleCustomers returns the
// accounts the login can reach, which are frequently manager (MCC) accounts that hold no
// campaigns themselves. The accounts that can run ads are their children, and each child
// must be addressed with the manager it was found under as login-customer-id — which is
// what AdsTarget.loginHint carries.
@Slf4j
@RequiredArgsConstructor
public class GoogleAdsAccountDiscovery {

  private final GoogleAdsApiClient adsClient;

  // Empty rather than an exception when the Google account owns no Google Ads account at
  // all. That is a normal state for a new user, distinct from an error, and the caller
  // turns it into actionable guidance.
  public List<String> accessibleCustomers(String accessToken) {
    try {
      return adsClient.listAccessibleCustomers(accessToken);
    } catch (GoogleAdsApiException e) {
      if (!e.isNotAdsUser()) {
        throw e;
      }
      log.debug("This Google account owns no Google Ads accounts: {}", e.getMessage());
      return List.of();
    }
  }

  public List<AdsTarget> discoverTargets(String accessToken) {
    Map<String, AdsTarget> byId = new LinkedHashMap<>();
    for (String accessibleId : accessibleCustomers(accessToken)) {
      List<GoogleAdAccountDto> clients;
      try {
        clients = adsClient.listCustomerClients(accessToken, accessibleId);
      } catch (GoogleAdsApiException e) {
        // A cancelled or suspended manager account fails its whole tree. The other
        // accessible customers are still worth listing, so skip rather than abort — one
        // dead account must not make the connect flow look broken.
        log.warn("Skipping Google Ads customer {}: {}", accessibleId, e.getMessage());
        continue;
      }
      for (GoogleAdAccountDto account : clients) {
        if (account.manager()) {
          // A manager account cannot run ads, so offering it as a target would produce a
          // confusing failure later rather than here.
          continue;
        }
        String name =
            account.name() == null || account.name().isBlank() ? account.id() : account.name();
        byId.putIfAbsent(
            account.id(),
            new AdsTarget(account.id(), name, account.currency(), account.loginCustomerId()));
      }
    }
    log.debug("Discovered {} manageable Google Ads accounts", byId.size());
    return new ArrayList<>(byId.values());
  }
}
