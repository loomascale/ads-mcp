package com.loomascale.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.consent.ConsentUrlResolver;
import com.loomascale.mcp.consent.ResourceOwnerAuthenticator;
import com.loomascale.mcp.consent.SingleOperatorAuthenticator;
import com.loomascale.mcp.oauth.AuthorizationCodeStore;
import com.loomascale.mcp.oauth.DynamicClientRegistrationService;
import com.loomascale.mcp.oauth.OAuthAccessTokenService;
import com.loomascale.mcp.oauth.OAuthAuthorizationService;
import com.loomascale.mcp.oauth.OAuthClientStore;
import com.loomascale.mcp.oauth.OAuthProperties;
import com.loomascale.mcp.oauth.OAuthTokenGrantService;
import com.loomascale.mcp.oauth.RefreshTokenFamilyRevoker;
import com.loomascale.mcp.oauth.RefreshTokenStore;
import com.loomascale.mcp.spi.McpAlertSink;
import com.loomascale.mcp.web.OAuthAuthorizeController;
import com.loomascale.mcp.web.OAuthRegisterController;
import com.loomascale.mcp.web.OAuthTokenController;
import com.loomascale.mcp.web.WellKnownController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

// The OAuth 2.1 authorization server: dynamic client registration, the authorize and
// token endpoints, and the two .well-known documents an MCP client uses to discover them.
@AutoConfiguration
public class McpOAuthAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OAuthProperties oauthProperties() {
    return new OAuthProperties();
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthAccessTokenService oauthAccessTokenService(OAuthProperties properties) {
    return new OAuthAccessTokenService(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public DynamicClientRegistrationService dynamicClientRegistrationService(
      OAuthClientStore clientStore,
      OAuthProperties properties,
      ObjectMapper objectMapper,
      McpAlertSink alertSink) {
    return new DynamicClientRegistrationService(clientStore, properties, objectMapper, alertSink);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthAuthorizationService oauthAuthorizationService(
      OAuthClientStore clientStore,
      AuthorizationCodeStore codeStore,
      DynamicClientRegistrationService registrationService,
      ConsentUrlResolver consentUrls,
      OAuthProperties properties) {
    return new OAuthAuthorizationService(
        clientStore, codeStore, registrationService, properties, consentUrls);
  }

  @Bean
  @ConditionalOnMissingBean
  public RefreshTokenFamilyRevoker refreshTokenFamilyRevoker(RefreshTokenStore store) {
    return new RefreshTokenFamilyRevoker(store);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthTokenGrantService oauthTokenGrantService(
      AuthorizationCodeStore codeStore,
      RefreshTokenStore refreshTokenStore,
      OAuthAccessTokenService accessTokenService,
      RefreshTokenFamilyRevoker familyRevoker,
      OAuthProperties properties,
      McpAlertSink alertSink) {
    return new OAuthTokenGrantService(
        codeStore, refreshTokenStore, accessTokenService, properties, alertSink, familyRevoker);
  }

  // Single-operator authentication is the default so that a fresh clone can actually
  // complete an OAuth flow. A deployment with real users replaces this one bean.
  @Bean
  @ConditionalOnMissingBean
  public ResourceOwnerAuthenticator resourceOwnerAuthenticator(McpProperties properties) {
    return new SingleOperatorAuthenticator(
        properties.getConsent().getOperatorPassword(), properties.getConsent().getUserId());
  }

  @Bean
  @ConditionalOnMissingBean
  public WellKnownController wellKnownController(OAuthProperties properties) {
    return new WellKnownController(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthRegisterController oauthRegisterController(
      DynamicClientRegistrationService registrationService) {
    return new OAuthRegisterController(registrationService);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthAuthorizeController oauthAuthorizeController(
      OAuthAuthorizationService authorizationService) {
    return new OAuthAuthorizeController(authorizationService);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthTokenController oauthTokenController(OAuthTokenGrantService tokenGrantService) {
    return new OAuthTokenController(tokenGrantService);
  }
}
