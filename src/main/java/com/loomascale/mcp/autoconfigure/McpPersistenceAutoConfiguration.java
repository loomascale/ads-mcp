package com.loomascale.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomascale.mcp.oauth.AuthorizationCodeStore;
import com.loomascale.mcp.oauth.OAuthClientStore;
import com.loomascale.mcp.oauth.RefreshTokenStore;
import com.loomascale.mcp.oauth.jdbc.JdbcAuthorizationCodeStore;
import com.loomascale.mcp.oauth.jdbc.JdbcOAuthClientStore;
import com.loomascale.mcp.oauth.jdbc.JdbcRefreshTokenStore;
import javax.sql.DataSource;
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
}
