package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdDto;
import com.loomascale.googleads.client.dto.GoogleAdTextAssetDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Lists individual Google ads with their policy approval status.
@Component
@RequiredArgsConstructor
public class GoogleListAdsTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_ads";
  }

  @Override
  public String description() {
    return "List individual ads in a Google Ads account with their status, policy approval verdict,"
        + " landing pages (the final URLs a click goes to) and, for responsive search ads, their"
        + " headlines, descriptions and display paths. Use this to answer where an ad sends people"
        + " and what it says. Optionally filtered by campaign or ad group. For campaigns and ad"
        + " groups, use google_list_campaigns. If you also run Meta ads, that server exposes"
        + " meta_list_ads.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(schema, "campaign_id", "string", "Only ads in this campaign.");
    McpSchemas.prop(schema, "ad_group_id", "string", "Only ads in this ad group.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the ads belong to.");
    ObjectNode ad = McpSchemas.objectArrayProp(schema, "ads", "Ads matching the filters.");
    McpSchemas.prop(ad, "id", "string", "Ad id.");
    McpSchemas.nullableProp(ad, "name", "string", "Ad name — often null for responsive ads.");
    McpSchemas.nullableProp(ad, "type", "string", "Ad type, e.g. RESPONSIVE_SEARCH_AD.");
    McpSchemas.nullableProp(ad, "status", "string", "Configured status, e.g. ENABLED or PAUSED.");
    McpSchemas.nullableProp(
        ad, "approvalStatus", "string", "Policy verdict, e.g. APPROVED or DISAPPROVED.");
    McpSchemas.prop(ad, "adGroupId", "string", "Ad group the ad belongs to.");
    McpSchemas.prop(ad, "campaignId", "string", "Campaign the ad belongs to.");
    McpSchemas.stringArrayProp(
        ad,
        "finalUrls",
        "Landing pages a click goes to. Usually one; empty when the ad type carries its URL"
            + " elsewhere.");
    ObjectNode headline =
        McpSchemas.objectArrayProp(
            ad, "headlines", "Responsive search ad headlines. Empty for other ad types.");
    McpSchemas.nullableProp(headline, "text", "string", "Headline text.");
    McpSchemas.nullableProp(
        headline,
        "pinned",
        "string",
        "Slot the headline is pinned to, e.g. HEADLINE_1. Null when Google may rotate it freely;"
            + " heavy pinning is a common reason an ad stops testing itself.");
    ObjectNode description =
        McpSchemas.objectArrayProp(
            ad, "descriptions", "Responsive search ad descriptions. Empty for other ad types.");
    McpSchemas.nullableProp(description, "text", "string", "Description text.");
    McpSchemas.nullableProp(description, "pinned", "string", "Slot it is pinned to, if any.");
    McpSchemas.nullableProp(ad, "path1", "string", "First display path segment, if set.");
    McpSchemas.nullableProp(ad, "path2", "string", "Second display path segment, if set.");
    McpSchemas.nullableProp(
        ad,
        "displayUrlPreview",
        "string",
        "Approximate URL a searcher sees — the final URL's host plus the display paths. Google"
            + " assembles the real one at serve time.");
    McpSchemas.required(schema, "customerId", "ads");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    return ToolAnnotations.readOnly();
  }

  @Override
  public boolean requiresWriteScope() {
    return false;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;
    String adGroupId = args.hasNonNull("ad_group_id") ? args.get("ad_group_id").asText() : null;

    List<GoogleAdDto> ads =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listAds(token, customerId, loginCustomerId, campaignId, adGroupId, null));

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    var arr = structured.putArray("ads");
    StringBuilder text = new StringBuilder("Ads in Google Ads account " + customerId + ":\n");
    for (GoogleAdDto ad : ads) {
      ObjectNode node = arr.addObject();
      node.put("id", ad.id());
      node.put("name", ad.name());
      node.put("type", ad.type());
      node.put("status", ad.status());
      node.put("approvalStatus", ad.approvalStatus());
      node.put("adGroupId", ad.adGroupId());
      node.put("campaignId", ad.campaignId());
      ArrayNode urls = node.putArray("finalUrls");
      ad.finalUrls().forEach(urls::add);
      writeAssets(node.putArray("headlines"), ad.headlines());
      writeAssets(node.putArray("descriptions"), ad.descriptions());
      node.put("path1", ad.path1());
      node.put("path2", ad.path2());
      node.put("displayUrlPreview", displayUrlPreview(ad));
      text.append("• ")
          .append(ad.name() == null ? ad.type() : ad.name())
          .append(" [")
          .append(ad.status())
          .append(ad.approvalStatus() != null ? ", " + ad.approvalStatus() : "")
          .append("] (ad ")
          .append(ad.id())
          .append(", ad group ")
          .append(ad.adGroupId())
          .append(")\n");
      if (!ad.finalUrls().isEmpty()) {
        text.append("    lands on: ").append(String.join(", ", ad.finalUrls())).append("\n");
      }
      if (!ad.headlines().isEmpty()) {
        text.append("    headlines: ").append(assetSummary(ad.headlines())).append("\n");
      }
      if (!ad.descriptions().isEmpty()) {
        text.append("    descriptions: ").append(assetSummary(ad.descriptions())).append("\n");
      }
    }
    if (ads.isEmpty()) {
      text.append("(no ads)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  private void writeAssets(ArrayNode arr, List<GoogleAdTextAssetDto> assets) {
    for (GoogleAdTextAssetDto asset : assets) {
      ObjectNode node = arr.addObject();
      node.put("text", asset.text());
      node.put("pinned", asset.pinnedField());
    }
  }

  // Pins matter enough to survive into the text summary: an ad whose every headline is
  // pinned is not testing anything.
  private String assetSummary(List<GoogleAdTextAssetDto> assets) {
    return String.join(
        " | ",
        assets.stream()
            .map(
                asset ->
                    asset.text()
                        + (asset.pinnedField() == null
                            ? ""
                            : " [pinned " + asset.pinnedField() + "]"))
            .toList());
  }

  // What a searcher roughly sees: the landing page host with the display paths appended.
  // Google composes the real display URL at serve time, so this is a preview, not a promise.
  private String displayUrlPreview(GoogleAdDto ad) {
    if (ad.finalUrls().isEmpty()) {
      return null;
    }
    String host;
    try {
      host = java.net.URI.create(ad.finalUrls().get(0)).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (host == null) {
      return null;
    }
    StringBuilder preview = new StringBuilder(host);
    if (ad.path1() != null && !ad.path1().isBlank()) {
      preview.append("/").append(ad.path1());
      if (ad.path2() != null && !ad.path2().isBlank()) {
        preview.append("/").append(ad.path2());
      }
    }
    return preview.toString();
  }
}
