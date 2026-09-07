package com.loomascale.mcp.spi;

// Platform keys. Deliberately strings rather than an enum: an enum in an SPI is closed, so
// a new platform could not be added without releasing the core artifact.
public final class AdsPlatforms {

  public static final String GOOGLE_ADS = "google_ads";
  public static final String META_ADS = "meta_ads";

  private AdsPlatforms() {}
}
