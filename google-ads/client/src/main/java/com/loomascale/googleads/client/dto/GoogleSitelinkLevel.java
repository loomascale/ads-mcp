package com.loomascale.googleads.client.dto;

import java.util.Optional;

// Where a sitelink is attached. The same SITELINK asset can be linked at three levels, and
// each level is a different GAQL resource, a different mutate service and a differently
// shaped link resource name — the account-level one being the odd one out, because a
// CustomerAsset has no owner id segment to join.
public enum GoogleSitelinkLevel {
  ACCOUNT("customer_asset", "customerAssets", "customerAsset", "customer"),
  CAMPAIGN("campaign_asset", "campaignAssets", "campaignAsset", "campaign"),
  AD_GROUP("ad_group_asset", "adGroupAssets", "adGroupAsset", "adGroup");

  private final String resource;
  private final String mutateService;
  private final String rowKey;
  private final String ownerRowKey;

  GoogleSitelinkLevel(String resource, String mutateService, String rowKey, String ownerRowKey) {
    this.resource = resource;
    this.mutateService = mutateService;
    this.rowKey = rowKey;
    this.ownerRowKey = ownerRowKey;
  }

  /** GAQL resource this level's links are reported from. */
  public String resource() {
    return resource;
  }

  /** REST mutate service that creates and removes this level's links. */
  public String mutateService() {
    return mutateService;
  }

  /** camelCased key the REST response carries the link row under. */
  public String rowKey() {
    return rowKey;
  }

  /** camelCased key the REST response carries the owning entity under. */
  public String ownerRowKey() {
    return ownerRowKey;
  }

  /** The tool-facing name: `account`, `campaign`, `ad_group`. */
  public String argument() {
    return name().toLowerCase();
  }

  public static Optional<GoogleSitelinkLevel> of(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String wanted = value.trim().toUpperCase();
    for (GoogleSitelinkLevel level : values()) {
      if (level.name().equals(wanted)) {
        return Optional.of(level);
      }
    }
    return Optional.empty();
  }
}
