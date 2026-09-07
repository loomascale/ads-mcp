package com.loomascale.googleads.mcp;

import com.loomascale.mcp.spi.AdsPlatformDescriptor;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.ProductBranding;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Google Ads vocabulary for the shared auth gate. Living here rather than in mcp-core is
// what lets that gate be written once without naming any platform.
@Component
@RequiredArgsConstructor
public class GoogleAdsPlatformDescriptor implements AdsPlatformDescriptor {

  // The single Google Ads scope. Google treats it as sensitive and gives it its own tick
  // box on the consent screen, so a user can grant openid/email/profile and still refuse
  // this one — which is why a healthy connection may still be unable to do anything.
  public static final String ADWORDS_SCOPE = "https://www.googleapis.com/auth/adwords";

  private final ProductBranding branding;

  @Override
  public String platformKey() {
    return AdsPlatforms.GOOGLE_ADS;
  }

  @Override
  public String displayName() {
    return "Google Ads";
  }

  @Override
  public String writeScope() {
    return ADWORDS_SCOPE;
  }

  // The grant is alive, so "reconnect" is the wrong instruction: the same consent would
  // land right back here. What is missing is an ad account, and once one exists the stored
  // refresh token picks it up with no second sign-in.
  @Override
  public Optional<String> noAdAccountMessage() {
    return Optional.of(
        "Your Google account is connected, but it does not own a Google Ads account yet —"
            + " or the only accounts it can reach are manager (MCC) accounts, which cannot"
            + " run ads themselves. Create a Google Ads account at https://ads.google.com/,"
            + " then open "
            + branding.connectionsUrl()
            + " and reconnect. You will not need to sign in again.");
  }
}
