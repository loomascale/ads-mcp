package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleLanguageCriterionDto;
import com.loomascale.googleads.client.dto.GoogleLocationCriterionDto;
import com.loomascale.googleads.client.dto.GoogleTargetingCriteriaDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.money.Money;
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

// Lists Google Ads campaigns (and their ad groups) with status and budgets —
// the Google twin of meta_list_campaigns.
@Component
@RequiredArgsConstructor
public class GoogleListCampaignsTool implements AdsTool {

  // A campaign can carry hundreds of city criteria; the listing reports the first few
  // and counts the rest instead of drowning the result.
  private static final int MAX_LOCATIONS_PER_CAMPAIGN = 25;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_campaigns";
  }

  @Override
  public String description() {
    return "List campaigns in a Google Ads account, including each campaign's status, channel type,"
        + " daily budget in the account's own currency (reported in the currency field), bidding"
        + " strategy, the Max CPC limit of Maximize clicks campaigns, the target CPA or target"
        + " ROAS an automated strategy bids to, the dates the campaign runs between, the locations"
        + " and languages"
        + " each campaign targets, and Google's own eligibility verdict per campaign"
        + " (primaryStatus) with the reasons it is not serving."
        + " Optionally includes ad groups. For individual ads, use google_list_ads. If you also"
        + " run Meta ads, that server exposes meta_list_campaigns.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.prop(schema, "include_ad_groups", "boolean", "Include ad groups. Default true.");
    McpSchemas.prop(
        schema,
        "include_targeting",
        "boolean",
        "Include the targeting of every campaign — the locations it targets or excludes and the"
            + " languages it selected. Included by default; set false for a leaner listing that"
            + " skips the criteria query.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account the campaigns belong to.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    ObjectNode campaign =
        McpSchemas.objectArrayProp(schema, "campaigns", "Campaigns in the account.");
    McpSchemas.prop(campaign, "id", "string", "Campaign id.");
    McpSchemas.nullableProp(campaign, "name", "string", "Campaign name.");
    McpSchemas.nullableProp(
        campaign, "status", "string", "Configured status, e.g. ENABLED or PAUSED.");
    McpSchemas.nullableProp(
        campaign,
        "primaryStatus",
        "string",
        "Google's own eligibility verdict, e.g. ELIGIBLE, LIMITED, NOT_ELIGIBLE or"
            + " MISCONFIGURED. A campaign can be ENABLED and still not serve, which plain status"
            + " does not show.");
    McpSchemas.stringArrayProp(
        campaign,
        "primaryStatusReasons",
        "Why the campaign is not serving, when it is not — e.g. BUDGET_MISCONFIGURED or"
            + " AD_GROUP_ADS_PAUSED. Empty when Google reports none. For account-level stops"
            + " (suspended account, billing setup not approved) use google_check_billing.");
    McpSchemas.nullableProp(
        campaign, "channelType", "string", "Advertising channel, e.g. SEARCH or PERFORMANCE_MAX.");
    McpSchemas.moneyProp(campaign, "dailyBudget", "Daily budget of the campaign.");
    McpSchemas.prop(
        campaign,
        "budgetShared",
        "boolean",
        "True when the budget is shared across campaigns — google_update_budget refuses those.");
    McpSchemas.nullableProp(
        campaign,
        "biddingStrategyType",
        "string",
        "Bidding strategy, e.g. TARGET_SPEND (Maximize clicks) or MANUAL_CPC. Manual bids on ad"
            + " groups and keywords only affect delivery under MANUAL_CPC.");
    McpSchemas.moneyProp(
        campaign,
        "maxCpc",
        "Max CPC limit of a Maximize clicks (TARGET_SPEND) campaign, null when there is none. A"
            + " limit set far below the market bid starves the campaign of impressions;"
            + " google_update_campaign_bidding changes it.");
    McpSchemas.moneyProp(
        campaign,
        "targetCpa",
        "Average cost per conversion a Maximize conversions campaign bids to, null when it has"
            + " none. google_update_campaign_bidding changes it.");
    // A ratio, so deliberately not moneyProp — that would attach the currency sentence.
    McpSchemas.nullableProp(
        campaign,
        "targetRoas",
        "number",
        "Return on ad spend a Maximize conversion value campaign bids to, as a RATIO where 4"
            + " means 400%. Null when the campaign has none."
            + " google_update_campaign_bidding changes it.");
    McpSchemas.nullableProp(
        campaign,
        "startDate",
        "string",
        "The date the campaign started, YYYY-MM-DD in the serving account's own timezone. Time"
            + " of day is not reported: a few campaign types can be scheduled to the hour,"
            + " and this is the day.");
    McpSchemas.nullableProp(
        campaign,
        "endDate",
        "string",
        "The date the campaign stops, YYYY-MM-DD. Null means it runs until it is paused. An end"
            + " date in the past is why a campaign that looks ENABLED is not spending;"
            + " google_update_campaign_settings changes it.");
    McpSchemas.nullableProp(
        campaign,
        "brandGuidelinesEnabled",
        "boolean",
        "Performance Max only: when true, Google holds the campaign's business name and logos on"
            + " the campaign rather than on each asset group, so google_update_brand_assets is"
            + " what edits them. Null on channels where the setting does not apply.");
    McpSchemas.nullableProp(
        campaign,
        "geoTargetType",
        "string",
        "How location targeting is applied: PRESENCE reaches only people in the targeted areas,"
            + " while PRESENCE_OR_INTEREST — Google's default — also reaches people elsewhere who"
            + " show interest in them, which is the usual reason a region-scoped campaign spends"
            + " outside its region. google_update_campaign_targeting changes it.");
    McpSchemas.prop(
        campaign,
        "biddingStrategyShared",
        "boolean",
        "True when a portfolio bidding strategy governs the campaign —"
            + " google_update_campaign_bidding refuses those.");
    // Present only when include_ad_groups is true.
    ObjectNode adGroup =
        McpSchemas.objectArrayProp(
            campaign, "adGroups", "Ad groups, only when include_ad_groups is true.");
    McpSchemas.prop(adGroup, "id", "string", "Ad group id.");
    McpSchemas.nullableProp(adGroup, "name", "string", "Ad group name.");
    McpSchemas.nullableProp(adGroup, "status", "string", "Configured status.");
    McpSchemas.nullableProp(adGroup, "type", "string", "Ad group type.");
    // Absent only when include_targeting was explicitly set false.
    ObjectNode location =
        McpSchemas.objectArrayProp(
            campaign,
            "locations",
            "Targeted and excluded locations, unless include_targeting was set false. An empty array"
                + " means the campaign targets all locations.");
    McpSchemas.nullableProp(
        location, "criterionId", "string", "Criterion id, needed to remove it.");
    McpSchemas.nullableProp(location, "id", "string", "Geo target constant id of the location.");
    McpSchemas.nullableProp(
        location, "name", "string", "Canonical name, e.g. Kyiv,Kyiv city,Ukraine.");
    McpSchemas.prop(
        location, "excluded", "boolean", "True when the location is excluded, not targeted.");
    McpSchemas.nullableProp(
        location,
        "bidModifier",
        "number",
        "Bid multiplier on this location, e.g. 1.2 for +20%. Null when the criterion has none.");
    McpSchemas.prop(
        campaign,
        "locationsTruncated",
        "integer",
        "How many further location criteria were not listed. Absent when include_targeting was set"
            + " false.");
    ObjectNode language =
        McpSchemas.objectArrayProp(
            campaign,
            "languages",
            "Languages the campaign selected, unless include_targeting was set false. An empty array"
                + " means the campaign serves to every language, which is Google's default.");
    McpSchemas.nullableProp(
        language, "criterionId", "string", "Criterion id, needed to remove it.");
    McpSchemas.nullableProp(language, "id", "string", "Language constant id, e.g. 1000.");
    McpSchemas.nullableProp(language, "code", "string", "ISO code, e.g. en or uk.");
    McpSchemas.nullableProp(language, "name", "string", "Language name, e.g. English.");
    McpSchemas.required(schema, "customerId", "campaigns");
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
    boolean includeAdGroups =
        !args.has("include_ad_groups") || args.get("include_ad_groups").asBoolean(true);
    boolean includeTargeting =
        !args.has("include_targeting") || args.get("include_targeting").asBoolean(true);

    List<GoogleCampaignDto> campaigns =
        adsService.call(
            connection,
            () -> adsService.client().listCampaigns(token, customerId, loginCustomerId));
    List<GoogleAdGroupDto> adGroups =
        includeAdGroups
            ? adsService.call(
                connection,
                () -> adsService.client().listAdGroups(token, customerId, loginCustomerId, null))
            : List.of();

    // One criteria query for the whole account, filtered per campaign below — same shape
    // as ad groups.
    GoogleTargetingCriteriaDto targeting =
        includeTargeting
            ? adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .listTargetingCriteria(token, customerId, loginCustomerId, null))
            : new GoogleTargetingCriteriaDto(List.of(), List.of());
    List<GoogleLocationCriterionDto> locations = targeting.locations();
    List<GoogleLanguageCriterionDto> languages = targeting.languages();

    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    ArrayNode arr = structured.putArray("campaigns");
    StringBuilder text = new StringBuilder("Campaigns in Google Ads account " + customerId + ":\n");
    for (GoogleCampaignDto c : campaigns) {
      ObjectNode node = arr.addObject();
      node.put("id", c.id());
      node.put("name", c.name());
      node.put("status", c.status());
      node.put("primaryStatus", c.primaryStatus());
      ArrayNode reasons = node.putArray("primaryStatusReasons");
      c.primaryStatusReasons().forEach(reasons::add);
      node.put("channelType", c.channelType());
      if (c.dailyBudgetCents() != null) {
        node.put("dailyBudget", Money.majorUnits(c.dailyBudgetCents()));
      } else {
        node.putNull("dailyBudget");
      }
      node.put("budgetShared", c.budgetExplicitlyShared());
      node.put("biddingStrategyType", c.biddingStrategyType());
      if (c.cpcBidCeilingCents() != null) {
        node.put("maxCpc", Money.majorUnits(c.cpcBidCeilingCents()));
      } else {
        node.putNull("maxCpc");
      }
      if (c.targetCpaCents() != null) {
        node.put("targetCpa", Money.majorUnits(c.targetCpaCents()));
      } else {
        node.putNull("targetCpa");
      }
      if (c.targetRoas() != null) {
        node.put("targetRoas", c.targetRoas());
      } else {
        node.putNull("targetRoas");
      }
      node.put("startDate", c.startDate());
      node.put("endDate", c.endDate());
      node.put("brandGuidelinesEnabled", c.brandGuidelinesEnabled());
      node.put("biddingStrategyShared", c.biddingStrategyResourceName() != null);
      node.put("geoTargetType", c.positiveGeoTargetType());
      text.append("• ")
          .append(c.name())
          .append(" [")
          .append(c.status())
          .append(", ")
          .append(c.channelType())
          .append("]")
          .append(
              c.dailyBudgetCents() != null
                  ? " " + Money.display(c.dailyBudgetCents(), currency) + "/day"
                  : "")
          .append(c.budgetExplicitlyShared() ? " (shared budget)" : "")
          .append(
              c.primaryStatusReasons().isEmpty()
                  ? ""
                  : " [not serving: " + String.join(", ", c.primaryStatusReasons()) + "]")
          .append(c.biddingStrategyType() != null ? " " + c.biddingStrategyType() : "")
          .append(
              c.cpcBidCeilingCents() != null
                  ? " max CPC " + Money.display(c.cpcBidCeilingCents(), currency)
                  : "")
          .append(" (")
          .append(c.id())
          .append(")\n");
      if (includeTargeting) {
        List<GoogleLocationCriterionDto> own =
            locations.stream().filter(l -> c.id().equals(l.campaignId())).toList();
        ArrayNode locationArr = node.putArray("locations");
        own.stream()
            .limit(MAX_LOCATIONS_PER_CAMPAIGN)
            .forEach(
                l -> {
                  ObjectNode ln = locationArr.addObject();
                  ln.put("criterionId", l.criterionId());
                  ln.put("id", l.geoTargetId());
                  ln.put("name", l.name());
                  ln.put("excluded", l.negative());
                  if (l.bidModifier() != null) {
                    ln.put("bidModifier", l.bidModifier());
                  } else {
                    ln.putNull("bidModifier");
                  }
                });
        node.put("locationsTruncated", Math.max(0, own.size() - MAX_LOCATIONS_PER_CAMPAIGN));
        text.append("    locations: ").append(locationSummary(own)).append("\n");

        List<GoogleLanguageCriterionDto> ownLanguages =
            languages.stream().filter(l -> c.id().equals(l.campaignId())).toList();
        ArrayNode languageArr = node.putArray("languages");
        ownLanguages.forEach(
            l -> {
              ObjectNode ln = languageArr.addObject();
              ln.put("criterionId", l.criterionId());
              ln.put("id", l.languageId());
              ln.put("code", l.code());
              ln.put("name", l.name());
            });
        text.append("    languages: ").append(languageSummary(ownLanguages)).append("\n");
      }
      if (includeAdGroups) {
        var adGroupArr = node.putArray("adGroups");
        adGroups.stream()
            .filter(g -> c.id().equals(g.campaignId()))
            .forEach(
                g -> {
                  ObjectNode gn = adGroupArr.addObject();
                  gn.put("id", g.id());
                  gn.put("name", g.name());
                  gn.put("status", g.status());
                  gn.put("type", g.type());
                });
      }
    }
    if (campaigns.isEmpty()) {
      text.append("(no campaigns)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  // "Kyiv,Kyiv city,Ukraine, excluded: Odesa,Odesa Oblast,Ukraine" — or the fact that
  // nothing is targeted, which Google reads as every location on earth.
  private String locationSummary(List<GoogleLocationCriterionDto> locations) {
    if (locations.isEmpty()) {
      return "all locations (no location criteria set)";
    }
    List<String> targeted =
        locations.stream().filter(l -> !l.negative()).map(this::locationLabel).toList();
    List<String> excluded =
        locations.stream()
            .filter(GoogleLocationCriterionDto::negative)
            .map(this::locationLabel)
            .toList();
    StringBuilder summary =
        new StringBuilder(
            targeted.isEmpty()
                ? "all locations (only exclusions set)"
                : String.join("; ", targeted));
    if (!excluded.isEmpty()) {
      summary.append(" — excluded: ").append(String.join("; ", excluded));
    }
    return summary.toString();
  }

  // No language criteria means every language — the campaign will happily show English
  // copy to somebody browsing Google in Ukrainian.
  private String languageSummary(List<GoogleLanguageCriterionDto> languages) {
    if (languages.isEmpty()) {
      return "all languages (no language criteria set)";
    }
    return String.join(
        "; ",
        languages.stream()
            .map(
                language ->
                    (language.name() == null ? language.languageId() : language.name())
                        + " ("
                        + language.criterionId()
                        + ")")
            .toList());
  }

  private String locationLabel(GoogleLocationCriterionDto location) {
    return (location.name() == null ? location.geoTargetId() : location.name())
        + " ("
        + location.criterionId()
        + ")";
  }
}
