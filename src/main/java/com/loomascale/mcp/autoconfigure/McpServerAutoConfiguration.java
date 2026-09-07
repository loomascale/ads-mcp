package com.loomascale.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.consent.ConsentPageRenderer;
import com.loomascale.mcp.consent.ConsentUrlResolver;
import com.loomascale.mcp.consent.DefaultConsentPageRenderer;
import com.loomascale.mcp.consent.ResourceOwnerAuthenticator;
import com.loomascale.mcp.defaults.LoggingAlertSink;
import com.loomascale.mcp.defaults.LoggingToolCallObserver;
import com.loomascale.mcp.defaults.UnlimitedQuotaPolicy;
import com.loomascale.mcp.protocol.McpProtocolService;
import com.loomascale.mcp.protocol.McpToolRegistry;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsPlatformDescriptor;
import com.loomascale.mcp.spi.McpAlertSink;
import com.loomascale.mcp.spi.McpToolCallObserver;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.spi.QuotaPolicy;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.web.BuiltinConsentPageController;
import com.loomascale.mcp.web.McpController;
import com.loomascale.mcp.web.OAuthConsentApiController;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

// Wires the MCP endpoint and the tool layer.
//
// Every bean is declared explicitly and guarded with @ConditionalOnMissingBean. There is
// deliberately no @ComponentScan: an autoconfiguration that scans its own package cannot
// be overridden selectively, and scanning from a library is how a host ends up with beans
// it never asked for. @ConditionalOnMissingBean IS the override mechanism — declare your
// own bean of the same type and yours wins.
//
// Tools are the exception, and stay @Component in the APPLICATION's own scanned package:
// McpToolRegistry simply collects every AdsTool bean the host defines.
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@EnableConfigurationProperties(McpProperties.class)
public class McpServerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public McpToolRegistry mcpToolRegistry(List<AdsTool> tools, ObjectMapper objectMapper) {
    return new McpToolRegistry(tools, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProductBranding productBranding(McpProperties properties) {
    return properties.toBranding();
  }

  @Bean
  @ConditionalOnMissingBean
  public QuotaPolicy quotaPolicy(McpProperties properties) {
    return new UnlimitedQuotaPolicy(
        Duration.ofSeconds(properties.getQuota().getTurnWindowSeconds()),
        properties.getQuota().getMaxCallsPerTurn());
  }

  @Bean
  @ConditionalOnMissingBean
  public McpAlertSink mcpAlertSink() {
    return new LoggingAlertSink();
  }

  // A list rather than one bean: a host may want its own observer AND keep the log line.
  // Only registered when the host has contributed none, so the default never doubles up.
  @Bean
  @ConditionalOnMissingBean(McpToolCallObserver.class)
  public McpToolCallObserver loggingToolCallObserver() {
    return new LoggingToolCallObserver();
  }

  @Bean
  @ConditionalOnMissingBean
  public McpAuthGate mcpAuthGate(
      AdsConnectionStore connectionStore,
      ProductBranding branding,
      List<AdsPlatformDescriptor> descriptors) {
    return new McpAuthGate(connectionStore, branding, descriptors);
  }

  @Bean
  @ConditionalOnMissingBean
  public McpProtocolService mcpProtocolService(
      McpToolRegistry registry,
      ObjectMapper objectMapper,
      QuotaPolicy quotaPolicy,
      ProductBranding branding,
      List<McpToolCallObserver> observers) {
    return new McpProtocolService(registry, objectMapper, quotaPolicy, branding, observers);
  }

  @Bean
  @ConditionalOnMissingBean
  public ConsentPageRenderer consentPageRenderer(
      McpProperties properties, ProductBranding branding) {
    return new DefaultConsentPageRenderer(branding.productName(), properties.getConsent().getPath());
  }

  @Bean
  @ConditionalOnMissingBean
  public BuiltinConsentPageController builtinConsentPageController(
      com.loomascale.mcp.oauth.OAuthAuthorizationService authorizationService,
      ResourceOwnerAuthenticator resourceOwner,
      ConsentPageRenderer renderer) {
    return new BuiltinConsentPageController(authorizationService, resourceOwner, renderer);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthConsentApiController oauthConsentApiController(
      com.loomascale.mcp.oauth.OAuthAuthorizationService authorizationService,
      ResourceOwnerAuthenticator resourceOwner) {
    return new OAuthConsentApiController(authorizationService, resourceOwner);
  }

  @Bean
  @ConditionalOnMissingBean
  public McpController mcpController(
      com.loomascale.mcp.oauth.OAuthAccessTokenService accessTokenService,
      com.loomascale.mcp.oauth.OAuthProperties oauthProperties,
      McpProtocolService protocolService,
      ObjectMapper objectMapper) {
    return new McpController(accessTokenService, oauthProperties, protocolService, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public ConsentUrlResolver consentUrlResolver(McpProperties properties) {
    return properties.toConsentUrlResolver();
  }

  // Runs last, so its report reflects the beans that were actually selected — in
  // particular whether the built-in credential store is in use or the host replaced it.
  @Bean
  public McpStartupCheck mcpStartupCheck(
      com.loomascale.mcp.oauth.OAuthProperties oauthProperties,
      McpProperties mcpProperties,
      org.springframework.beans.factory.ObjectProvider<com.loomascale.mcp.security.TokenCipher>
          tokenCipher,
      com.loomascale.mcp.spi.AdsConnectionStore connectionStore) {
    com.loomascale.mcp.security.TokenCipher cipher = tokenCipher.getIfAvailable();
    return new McpStartupCheck(
        oauthProperties,
        mcpProperties,
        cipher != null && cipher.isConfigured(),
        connectionStore instanceof com.loomascale.mcp.spi.jdbc.JdbcAdsConnectionStore);
  }
}
