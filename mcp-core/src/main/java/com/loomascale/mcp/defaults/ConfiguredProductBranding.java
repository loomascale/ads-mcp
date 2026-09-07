package com.loomascale.mcp.defaults;

import com.loomascale.mcp.spi.ProductBranding;

// Branding from configuration. The defaults are deliberately generic — "this server" reads
// correctly in a sentence a model relays to a user, where a blank or placeholder name
// would not.
public class ConfiguredProductBranding implements ProductBranding {

  private final String productName;
  private final String serverName;
  private final String connectionsUrl;
  private final String consentUrlTemplate;

  public ConfiguredProductBranding(
      String productName, String serverName, String connectionsUrl, String consentUrlTemplate) {
    this.productName = productName;
    this.serverName = serverName;
    this.connectionsUrl = connectionsUrl;
    this.consentUrlTemplate = consentUrlTemplate;
  }

  @Override
  public String productName() {
    return productName;
  }

  @Override
  public String serverName() {
    return serverName;
  }

  @Override
  public String connectionsUrl() {
    return connectionsUrl;
  }

  @Override
  public String consentUrl(String requestId) {
    return consentUrlTemplate.replace("{request_id}", requestId);
  }
}
