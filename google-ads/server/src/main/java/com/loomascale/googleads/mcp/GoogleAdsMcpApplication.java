package com.loomascale.googleads.mcp;

import com.loomascale.googleads.mcp.connect.GoogleOAuthConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// The runnable server.
//
// mcp-core contributes /mcp, the OAuth endpoints, the consent screen and the credential
// store through its autoconfiguration, so nothing here has to wire them. What this package
// adds is the Google-specific half — the tools, the platform descriptor, the token
// refresher and the connect flow — picked up by ordinary component scanning.
@SpringBootApplication
@EnableConfigurationProperties(GoogleOAuthConfig.class)
public class GoogleAdsMcpApplication {

  public static void main(String[] args) {
    SpringApplication.run(GoogleAdsMcpApplication.class, args);
  }
}
