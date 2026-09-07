package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleSitelinkDto;
import com.loomascale.googleads.client.dto.GoogleSitelinkLevel;
import com.loomascale.googleads.client.dto.GoogleSitelinkQuery;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.McpToolException;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Sitelinks: the extra links under a Search ad, and the cheapest click-through win in the
// account. Nothing else on the connector could see them, so an audit could read every
// keyword and every ad and still not notice that half the campaigns show no sitelinks at all
// or that one of them points at a page that no longer exists.
//
// The three levels are read separately because Google keeps them apart: the same sitelink can
// be linked to the account, to a campaign and to an ad group, and only the narrowest link
// serves. A campaign showing none of its own is inheriting the account's.
@Component
@RequiredArgsConstructor
public class GoogleListSitelinksTool implements AdsTool {

  // GAQL DURING keywords the tool accepts as date_preset.
  private static final List<String> DATE_PRESETS =
      List.of(
          "TODAY",
          "YESTERDAY",
          "LAST_7_DAYS",
          "LAST_14_DAYS",
          "LAST_30_DAYS",
          "THIS_MONTH",
          "LAST_MONTH");

  private static final List<String> LEVELS = List.of("all", "account", "campaign", "ad_group");

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_sitelinks";
  }

  @Override
  public String description() {
    return "List the sitelinks of a Google Ads account — the extra links shown under a Search ad —"
        + " with their link text, the two description lines, the page each one sends people to,"
        + " and whether they are attached to the whole account, to one campaign or to one ad"
        + " group. Use this to find campaigns running without sitelinks, sitelinks pointing at"
        + " dead or generic pages, and sitelinks that just repeat the ad's own headlines. Set"
        + " include_metrics to also get each sitelink's clicks, impressions, cost and conversions"
        + " for the range; the clicks are split into clicks on the sitelink itself and clicks"
        + " elsewhere in the ad while it was showing, because Google reports the two together and"
        + " only the first belongs to the sitelink. For the ads themselves use google_list_ads,"
        + " and for Performance Max asset groups use google_list_asset_groups.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    ObjectNode level =
        McpSchemas.prop(
            schema,
            "level",
            "string",
            "Which link level to read. Default all: the account's own sitelinks, the ones on"
                + " campaigns and the ones on ad groups.");
    McpSchemas.enumValues(level, LEVELS);
    McpSchemas.prop(schema, "campaign_id", "string", "Only sitelinks linked to this campaign.");
    McpSchemas.prop(schema, "ad_group_id", "string", "Only sitelinks linked to this ad group.");
    McpSchemas.prop(
        schema,
        "include_metrics",
        "boolean",
        "Also return each sitelink's clicks, impressions, cost and conversions. Default false —"
            + " it costs a second query per level.");
    ObjectNode preset =
        McpSchemas.prop(
            schema,
            "date_preset",
            "string",
            "Preset range for the metrics. Default LAST_30_DAYS. Ignored when since/until are"
                + " set, and irrelevant without include_metrics.");
    McpSchemas.enumValues(preset, DATE_PRESETS);
    McpSchemas.prop(schema, "since", "string", "Custom range start, YYYY-MM-DD.");
    McpSchemas.prop(schema, "until", "string", "Custom range end, YYYY-MM-DD.");
    McpSchemas.prop(schema, "limit", "integer", "Max rows per level. Default 100, max 1000.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the sitelinks belong to.");
    ObjectNode sitelink =
        McpSchemas.objectArrayProp(schema, "sitelinks", "One row per sitelink link.");
    McpSchemas.prop(
        sitelink,
        "level",
        "string",
        "Where the sitelink is attached: account, campaign or ad_group.");
    McpSchemas.nullableProp(
        sitelink,
        "ownerId",
        "string",
        "Campaign or ad group holding the link. Null at account"
            + " level, where the link belongs to the whole account.");
    McpSchemas.nullableProp(sitelink, "ownerName", "string", "Name of that campaign or ad group.");
    McpSchemas.nullableProp(
        sitelink,
        "assetId",
        "string",
        "Sitelink asset id — what" + " google_update_sitelink and google_remove_sitelinks take.");
    McpSchemas.nullableProp(sitelink, "linkText", "string", "The clickable line, max 25 chars.");
    McpSchemas.nullableProp(sitelink, "description1", "string", "First description line.");
    McpSchemas.nullableProp(sitelink, "description2", "string", "Second description line.");
    McpSchemas.stringArrayProp(sitelink, "finalUrls", "Pages a click lands on.");
    McpSchemas.nullableProp(sitelink, "status", "string", "ENABLED or PAUSED.");
    McpSchemas.nullableProp(
        sitelink, "primaryStatus", "string", "Whether the sitelink is eligible to serve.");
    McpSchemas.nullableProp(
        sitelink,
        "clicksOnSitelink",
        "integer",
        "Clicks on this sitelink over the range. Present only with include_metrics.");
    McpSchemas.nullableProp(
        sitelink,
        "clicksOnAdWithSitelink",
        "integer",
        "Clicks elsewhere in the ad while this sitelink was showing — NOT the sitelink's own"
            + " clicks. Present only with include_metrics.");
    McpSchemas.nullableProp(
        sitelink,
        "impressions",
        "integer",
        "Impressions of ads showing this sitelink. Present only with include_metrics.");
    McpSchemas.moneyProp(sitelink, "cost", "Cost of the clicks on this sitelink over the range.");
    McpSchemas.nullableProp(
        sitelink,
        "conversions",
        "number",
        "Conversions from clicks on this sitelink. Present only with include_metrics.");
    McpSchemas.prop(
        schema,
        "dateRange",
        "string",
        "Range the metrics cover, or none when they were not asked" + " for.");
    McpSchemas.prop(
        schema,
        "truncated",
        "boolean",
        "True when a level hit the row cap, so more sitelinks exist than were returned.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.required(schema, "customerId", "sitelinks", "dateRange", "truncated");
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
    // Arguments first: decrypting a token can refresh it against Google, which is wasted work
    // on a call that cannot run.
    boolean includeMetrics =
        args.hasNonNull("include_metrics") && args.get("include_metrics").asBoolean(false);
    String since = args.hasNonNull("since") ? args.get("since").asText() : null;
    String until = args.hasNonNull("until") ? args.get("until").asText() : null;
    String preset = null;
    if (since == null || until == null) {
      preset = args.hasNonNull("date_preset") ? args.get("date_preset").asText() : "LAST_30_DAYS";
      if (!DATE_PRESETS.contains(preset)) {
        throw new McpToolException(
            "date_preset must be one of: " + String.join(", ", DATE_PRESETS));
      }
      since = null;
      until = null;
    }
    String levelArg = args.hasNonNull("level") ? args.get("level").asText() : "all";
    if (!LEVELS.contains(levelArg)) {
      throw new McpToolException("level must be one of: " + String.join(", ", LEVELS));
    }
    List<GoogleSitelinkLevel> levels =
        "all".equals(levelArg)
            ? List.of(
                GoogleSitelinkLevel.ACCOUNT,
                GoogleSitelinkLevel.CAMPAIGN,
                GoogleSitelinkLevel.AD_GROUP)
            : List.of(
                GoogleSitelinkLevel.of(levelArg)
                    .orElseThrow(() -> new McpToolException("Unknown level: " + levelArg)));
    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;
    String adGroupId = args.hasNonNull("ad_group_id") ? args.get("ad_group_id").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : 0;

    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency =
        includeMetrics
            ? adsService.currencyCode(connection, token, customerId, loginCustomerId)
            : null;

    GoogleSitelinkQuery query =
        new GoogleSitelinkQuery(
            levels, campaignId, adGroupId, preset, since, until, limit, includeMetrics);
    List<GoogleSitelinkDto> sitelinks =
        adsService.call(
            connection,
            () -> adsService.client().listSitelinks(token, customerId, loginCustomerId, query));

    String range = includeMetrics ? (preset != null ? preset : since + ".." + until) : "none";
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("dateRange", range);
    structured.put("truncated", truncated(sitelinks, limit));
    ArrayNode arr = structured.putArray("sitelinks");
    StringBuilder text = new StringBuilder("Sitelinks in Google Ads account " + customerId);
    text.append(includeMetrics ? " (" + range + "):\n" : ":\n");

    for (GoogleSitelinkDto sitelink : sitelinks) {
      ObjectNode node = arr.addObject();
      node.put("level", sitelink.level().argument());
      node.put("ownerId", sitelink.ownerId());
      node.put("ownerName", sitelink.ownerName());
      node.put("assetId", sitelink.assetId());
      node.put("linkText", sitelink.linkText());
      node.put("description1", sitelink.description1());
      node.put("description2", sitelink.description2());
      ArrayNode urls = node.putArray("finalUrls");
      sitelink.finalUrls().forEach(urls::add);
      node.put("status", sitelink.linkStatus());
      node.put("primaryStatus", sitelink.primaryStatus());
      if (sitelink.hasMetrics()) {
        node.put("clicksOnSitelink", sitelink.clicksOnSitelink());
        node.put("clicksOnAdWithSitelink", sitelink.clicksOnAdWithSitelink());
        node.put("impressions", sitelink.impressions());
        node.put("cost", Money.majorUnits(sitelink.costCents()));
        node.put("conversions", sitelink.conversions());
      }
      text.append(describe(sitelink, currency));
    }
    if (sitelinks.isEmpty()) {
      text.append(
          "(no sitelinks — this account shows none of the extra links under its Search ads, which"
              + " is one of the cheapest click-through wins available. Add them with"
              + " google_create_sitelinks)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  // One line per sitelink, plus the two findings worth naming without being asked: a sitelink
  // with nowhere to send a click, and one that showed but was never clicked.
  private String describe(GoogleSitelinkDto sitelink, String currency) {
    StringBuilder line = new StringBuilder();
    line.append("• [")
        .append(sitelink.level().argument())
        .append(sitelink.ownerName() == null ? "" : " " + sitelink.ownerName())
        .append("] \"")
        .append(sitelink.linkText())
        .append("\" (asset ")
        .append(sitelink.assetId())
        .append(", ")
        .append(sitelink.linkStatus())
        .append(")\n");
    line.append("    lands on: ")
        .append(
            sitelink.finalUrls().isEmpty()
                ? "(nothing — this sitelink has no landing page and cannot serve)"
                : String.join(", ", sitelink.finalUrls()))
        .append("\n");
    if (sitelink.description1() != null) {
      line.append("    ")
          .append(sitelink.description1())
          .append(" / ")
          .append(sitelink.description2())
          .append("\n");
    }
    if (sitelink.hasMetrics()) {
      line.append("    ")
          .append(sitelink.clicksOnSitelink())
          .append(" clicks on the sitelink, ")
          .append(sitelink.impressions())
          .append(" impressions, ")
          .append(Money.display(sitelink.costCents(), currency))
          .append(", ")
          .append(sitelink.conversions())
          .append(" conversions")
          .append(
              sitelink.impressions() > 0 && sitelink.clicksOnSitelink() == 0
                  ? " [shown but never clicked]"
                  : "")
          .append("\n");
    }
    return line.toString();
  }

  // Each level is its own query with its own cap, so any level that came back full means the
  // view is partial.
  private boolean truncated(List<GoogleSitelinkDto> sitelinks, int limit) {
    int cap = GoogleAdsApiClient.clampLimit(limit);
    for (GoogleSitelinkLevel level : GoogleSitelinkLevel.values()) {
      long count = sitelinks.stream().filter(sitelink -> sitelink.level() == level).count();
      if (count >= cap) {
        return true;
      }
    }
    return false;
  }
}
