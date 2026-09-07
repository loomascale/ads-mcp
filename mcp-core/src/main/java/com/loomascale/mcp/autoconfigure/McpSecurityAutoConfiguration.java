package com.loomascale.mcp.autoconfigure;

import com.loomascale.mcp.security.HostResolver;
import com.loomascale.mcp.security.ImageDimensionReader;
import com.loomascale.mcp.security.OutboundUrlPolicy;
import com.loomascale.mcp.security.SafeImageFetcher;
import com.loomascale.mcp.security.SystemHostResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

// SSRF-safe fetching of creative images.
//
// Tools that attach an image to an ad take a public URL from the model, which makes this a
// genuine SSRF surface: without a policy, "https://169.254.169.254/..." is a request the
// server makes on an attacker's behalf from inside its own network.
//
// HostResolver is separated out so the policy can be tested against fixed addresses rather
// than whatever DNS happens to return, and so a deployment can supply its own resolution.
@AutoConfiguration
public class McpSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public HostResolver hostResolver() {
    return new SystemHostResolver();
  }

  @Bean
  @ConditionalOnMissingBean
  public OutboundUrlPolicy outboundUrlPolicy(
      HostResolver hostResolver,
      @Value("${mcp.image-upload.allow-http:false}") boolean allowHttp,
      @Value("${mcp.image-upload.blocked-hosts:}") String blockedHosts) {
    return new OutboundUrlPolicy(hostResolver, allowHttp, blockedHosts);
  }

  @Bean
  @ConditionalOnMissingBean
  public ImageDimensionReader imageDimensionReader() {
    return new ImageDimensionReader();
  }

  @Bean
  @ConditionalOnMissingBean
  public SafeImageFetcher safeImageFetcher(
      OutboundUrlPolicy policy,
      @Value("${mcp.image-upload.max-bytes:8388608}") long maxBytes,
      @Value("${mcp.image-upload.min-bytes:512}") long minBytes,
      @Value("${mcp.image-upload.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${mcp.image-upload.read-timeout-ms:10000}") long readTimeoutMs,
      @Value("${mcp.image-upload.total-timeout-ms:20000}") long totalTimeoutMs,
      @Value("${mcp.image-upload.max-redirects:3}") int maxRedirects,
      @Value("${mcp.image-upload.allowed-content-types:image/jpeg,image/png}")
          String allowedContentTypes,
      @Value("${mcp.image-upload.user-agent:ads-mcp/1.0}") String userAgent) {
    return new SafeImageFetcher(
        policy,
        maxBytes,
        minBytes,
        connectTimeoutMs,
        readTimeoutMs,
        totalTimeoutMs,
        maxRedirects,
        allowedContentTypes,
        userAgent);
  }
}
