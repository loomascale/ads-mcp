package com.loomascale.googleads.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.GoogleAdsApiConfig;
import com.loomascale.googleads.mcp.connect.GoogleAdsAccountDiscovery;
import com.loomascale.googleads.mcp.connect.GoogleOAuthConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Beans for the framework-free client classes, which cannot annotate themselves.
@Configuration
public class GoogleAdsConfiguration {

  // The client takes a plain record rather than reading configuration itself, so that it
  // stays usable outside Spring. Translating config into it is this layer's job.
  @Bean
  public GoogleAdsApiConfig googleAdsApiConfig(GoogleOAuthConfig oauthConfig) {
    return new GoogleAdsApiConfig(oauthConfig.apiVersion(), oauthConfig.developerToken());
  }

  @Bean
  public GoogleAdsApiClient googleAdsApiClient(
      ObjectMapper objectMapper, GoogleAdsApiConfig config) {
    return new GoogleAdsApiClient(objectMapper, config);
  }

  @Bean
  public GoogleAdsAccountDiscovery googleAdsAccountDiscovery(GoogleAdsApiClient adsClient) {
    return new GoogleAdsAccountDiscovery(adsClient);
  }
}
