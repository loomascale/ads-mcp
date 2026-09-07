package com.loomascale.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.audit.WriteAuditStore;
import com.loomascale.mcp.audit.jdbc.JdbcWriteAuditStore;
import com.loomascale.mcp.oauth.AuthorizationCodeStore;
import com.loomascale.mcp.oauth.OAuthClientStore;
import com.loomascale.mcp.oauth.RefreshTokenStore;
import com.loomascale.mcp.oauth.jdbc.JdbcAuthorizationCodeStore;
import com.loomascale.mcp.oauth.jdbc.JdbcOAuthClientStore;
import com.loomascale.mcp.oauth.jdbc.JdbcRefreshTokenStore;
import com.loomascale.mcp.security.TokenCipher;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsTokenRefresher;
import com.loomascale.mcp.spi.jdbc.JdbcAdsConnectionStore;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

// JDBC-backed OAuth storage.
//
// Only activates when the application actually has a DataSource, so adding this library to
// something with no database does not fail at startup — it simply leaves the stores
// undefined, and a host that wants them elsewhere supplies its own beans.
//
// JdbcTemplate rather than JPA is a deliberate constraint, not a preference: an
// autoconfiguration cannot contribute @EntityScan or @EnableJpaRepositories without
// REPLACING the host application's own scanning packages, which would silently stop the
// host's entities being discovered the moment it added this dependency.
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
public class McpPersistenceAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public JdbcTemplate mcpJdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean
  public OAuthClientStore oauthClientStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return new JdbcOAuthClientStore(jdbc, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public AuthorizationCodeStore authorizationCodeStore(JdbcTemplate jdbc) {
    return new JdbcAuthorizationCodeStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean
  public RefreshTokenStore refreshTokenStore(JdbcTemplate jdbc) {
    return new JdbcRefreshTokenStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean
  public WriteAuditStore writeAuditStore(JdbcTemplate jdbc) {
    return new JdbcWriteAuditStore(jdbc);
  }

  // No default for the key. A blank one leaves the cipher unusable rather than encrypting
  // with something guessable, and the failure surfaces on first use with instructions.
  @Bean
  @ConditionalOnMissingBean
  public TokenCipher tokenCipher(@Value("${mcp.token-encryption-key:}") String base64Key) {
    return new TokenCipher(base64Key);
  }

  // The built-in credential store, so that supplying credentials does not require
  // implementing an interface first. Replaced wholesale by declaring your own bean.
  @Bean
  @ConditionalOnMissingBean
  public AdsConnectionStore adsConnectionStore(
      JdbcTemplate jdbc,
      TokenCipher cipher,
      ObjectMapper objectMapper,
      List<AdsTokenRefresher> refreshers) {
    return new JdbcAdsConnectionStore(jdbc, cipher, objectMapper, refreshers);
  }
}
