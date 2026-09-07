package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleCreateCampaignSpec;
import com.loomascale.googleads.client.dto.GoogleResolvedLanguage;
import com.loomascale.googleads.client.dto.GoogleResolvedLocation;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.guardrail.BudgetGuardrailService;
import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.McpToolException;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Creates a full Google Search campaign (budget → campaign → ad group →
// responsive search ad → keywords), ALWAYS PAUSED. The tool schema has no status
// field, so the model cannot make anything live — activation is a separate,
// guarded tool.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCreateCampaignTool implements AdsTool {

  // Google's responsive-search-ad limits live in GoogleAdsEditSupport, shared with
  // google_update_ad so the two cannot disagree.
  private static final int MIN_HEADLINES = GoogleAdsEditSupport.MIN_HEADLINES;
  private static final int MAX_HEADLINES = GoogleAdsEditSupport.MAX_HEADLINES;
  private static final int MAX_HEADLINE_LENGTH = GoogleAdsEditSupport.MAX_HEADLINE_LENGTH;
  private static final int MIN_DESCRIPTIONS = GoogleAdsEditSupport.MIN_DESCRIPTIONS;
  private static final int MAX_DESCRIPTIONS = GoogleAdsEditSupport.MAX_DESCRIPTIONS;
  private static final int MAX_DESCRIPTION_LENGTH = GoogleAdsEditSupport.MAX_DESCRIPTION_LENGTH;
  private static final int MAX_KEYWORDS = 50;
  private static final int MAX_CREATES_PER_DAY = 10;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_create_campaign";
  }

  @Override
  public String description() {
    return "Create a Google Ads Search campaign with a responsive search ad and keywords. The"
        + " campaign is created PAUSED — nothing spends until you run google_activate_campaign."
        + " Headlines are limited to 30 characters each (3-15 of them) and descriptions to 90"
        + " characters each (2-4). Pass locations to scope it geographically — without them the"
        + " campaign targets every location on earth, which is Google's default; use"
        + " google_find_locations to turn place names into ids. Pass languages the same way, or the"
        + " ads show to people browsing Google in any language. If you also run Meta ads, that"
        + " server exposes meta_create_campaign.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "name", "string", "Campaign name.");
    McpSchemas.prop(
        schema, "daily_budget", "number", McpSchemas.moneyInputDescription("Daily budget."));
    McpSchemas.prop(schema, "final_url", "string", "Landing page URL the ad clicks through to.");
    McpSchemas.stringArrayProp(
        schema, "headlines", "3-15 ad headlines, each 30 characters or fewer.");
    McpSchemas.stringArrayProp(
        schema, "descriptions", "2-4 ad descriptions, each 90 characters or fewer.");
    McpSchemas.stringArrayProp(
        schema, "keywords", "Search keywords to target (broad match), at least one.");
    McpSchemas.stringArrayProp(
        schema,
        "locations",
        "Locations the campaign may show in — geo target constant ids or place names, at most "
            + GoogleAdsEditSupport.MAX_LOCATIONS
            + ". Omit only when the campaign really should reach every country; a campaign with no"
            + " location criteria targets all locations.");
    McpSchemas.stringArrayProp(
        schema,
        "languages",
        "Languages the campaign may show in — language constant ids, ISO codes (en, uk) or names"
            + " (English), at most "
            + GoogleAdsEditSupport.MAX_LOCATIONS
            + ". A campaign with no language criteria shows to every language, matching the"
            + " language a person browses Google in, not the language of the keywords.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(
        schema, "name", "daily_budget", "final_url", "headlines", "descriptions", "keywords");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Id of the campaign that was created.");
    McpSchemas.prop(schema, "adGroupId", "string", "Id of the ad group created inside it.");
    McpSchemas.prop(schema, "adId", "string", "Id of the responsive search ad created.");
    ObjectNode status =
        McpSchemas.prop(
            schema,
            "status",
            "string",
            "Always PAUSED — nothing spends until google_activate_campaign runs.");
    McpSchemas.enumValues(status, List.of("PAUSED"));
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "dailyBudget", "Daily budget of the new campaign.");
    ObjectNode location =
        McpSchemas.objectArrayProp(
            schema,
            "locations",
            "Locations the campaign targets. Empty means all locations, Google's default.");
    McpSchemas.nullableProp(location, "id", "string", "Geo target constant id.");
    McpSchemas.nullableProp(location, "name", "string", "Canonical name of the location.");
    ObjectNode language =
        McpSchemas.objectArrayProp(
            schema,
            "languages",
            "Languages the campaign shows in. Empty means every language, Google's default.");
    McpSchemas.nullableProp(language, "id", "string", "Language constant id.");
    McpSchemas.nullableProp(language, "code", "string", "ISO code, e.g. en.");
    McpSchemas.nullableProp(language, "name", "string", "Language name, e.g. English.");
    McpSchemas.required(schema, "campaignId", "adGroupId", "adId", "status", "dailyBudget");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    return ToolAnnotations.write(false, false);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);

    if (audit.countToday(userId, name()) >= MAX_CREATES_PER_DAY) {
      throw new McpToolException(
          "Daily limit of " + MAX_CREATES_PER_DAY + " new campaigns reached. Try again tomorrow.");
    }

    GoogleCreateCampaignSpec spec = parseSpec(args);

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    // Resolved before the guardrail runs so a refusal names the account currency.
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);
    guardrail.enforce(connection, spec.dailyBudgetCents(), currency);
    // Resolved before anything is created: an unknown place name must not leave a half
    // built campaign behind.
    List<GoogleResolvedLocation> locations =
        support.resolveLocations(
            connection, token, customerId, loginCustomerId, spec.locations(), "locations");
    List<GoogleResolvedLanguage> languages =
        support.resolveLanguages(
            connection, token, customerId, loginCustomerId, spec.languages(), "languages");

    // Track created resource names for best-effort rollback on partial failure.
    List<String> created = new ArrayList<>();
    String campaignId = null;
    String argsSummary = "name=" + spec.name() + ";dailyBudgetCents=" + spec.dailyBudgetCents();
    try {
      String budgetResourceName =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createCampaignBudget(
                          token,
                          customerId,
                          loginCustomerId,
                          spec.name() + " — Budget",
                          GoogleAdsApiClient.centsToMicros(spec.dailyBudgetCents())));
      created.add(budgetResourceName);

      String campaignResourceName =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createSearchCampaign(
                          token, customerId, loginCustomerId, spec.name(), budgetResourceName));
      created.add(campaignResourceName);
      campaignId = lastSegment(campaignResourceName);

      if (!locations.isEmpty()) {
        List<String> geoTargetIds =
            locations.stream().map(GoogleResolvedLocation::geoTargetId).toList();
        created.addAll(
            adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .addLocationCriteria(
                            token,
                            customerId,
                            loginCustomerId,
                            campaignResourceName,
                            geoTargetIds,
                            false)));
      }

      if (!languages.isEmpty()) {
        List<String> languageIds = languages.stream().map(GoogleResolvedLanguage::id).toList();
        created.addAll(
            adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .addLanguageCriteria(
                            token,
                            customerId,
                            loginCustomerId,
                            campaignResourceName,
                            languageIds)));
      }

      String adGroupResourceName =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createAdGroup(
                          token,
                          customerId,
                          loginCustomerId,
                          spec.name() + " — Ad Group",
                          campaignResourceName));
      created.add(adGroupResourceName);
      String adGroupId = lastSegment(adGroupResourceName);

      String adResourceName =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createResponsiveSearchAd(
                          token,
                          customerId,
                          loginCustomerId,
                          adGroupResourceName,
                          spec.finalUrl(),
                          spec.headlines(),
                          spec.descriptions()));
      created.add(adResourceName);
      // adGroupAds resource ids are {adGroupId}~{adId}.
      String adId = lastSegment(adResourceName);
      int tilde = adId.indexOf('~');
      if (tilde >= 0) {
        adId = adId.substring(tilde + 1);
      }

      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .addKeywords(
                    token, customerId, loginCustomerId, adGroupResourceName, spec.keywords());
            return null;
          });

      audit.record(userId, name(), WriteKind.CREATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_create_campaign</b>\nUser: "
              + userId
              + "\nCampaign: "
              + spec.name()
              + " ("
              + Money.display(spec.dailyBudgetCents(), currency)
              + "/day, PAUSED)\nLocations: "
              + (locations.isEmpty()
                  ? "ALL"
                  : String.join(
                      ", ", locations.stream().map(GoogleResolvedLocation::name).toList()))
              + "\nLanguages: "
              + (languages.isEmpty()
                  ? "ALL"
                  : String.join(
                      ", ", languages.stream().map(GoogleResolvedLanguage::name).toList())));

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("campaignId", campaignId);
      structured.put("adGroupId", adGroupId);
      structured.put("adId", adId);
      structured.put("status", "PAUSED");
      structured.put("currency", currency);
      structured.put("dailyBudget", Money.majorUnits(spec.dailyBudgetCents()));
      ArrayNode locationArr = structured.putArray("locations");
      for (GoogleResolvedLocation location : locations) {
        ObjectNode node = locationArr.addObject();
        node.put("id", location.geoTargetId());
        node.put("name", location.name());
      }
      ArrayNode languageArr = structured.putArray("languages");
      for (GoogleResolvedLanguage language : languages) {
        ObjectNode node = languageArr.addObject();
        node.put("id", language.id());
        node.put("code", language.code());
        node.put("name", language.name());
      }
      String text =
          "Created Google Search campaign \""
              + spec.name()
              + "\" (id "
              + campaignId
              + ") — PAUSED at "
              + Money.display(spec.dailyBudgetCents(), currency)
              + "/day with "
              + spec.keywords().size()
              + " keywords, "
              + locationSummary(locations)
              + ", "
              + languageSummary(languages)
              + ". Review it, then run google_activate_campaign to go live.";
      return ToolResult.ok(text, structured);
    } catch (RuntimeException e) {
      // Best-effort rollback of whatever we managed to create.
      rollback(token, customerId, loginCustomerId, created);
      audit.record(
          userId, name(), WriteKind.CREATE, argsSummary, campaignId, false, e.getMessage());
      throw e;
    }
  }

  private GoogleCreateCampaignSpec parseSpec(JsonNode args) {
    String name = requireText(args, "name");
    if (!args.hasNonNull("daily_budget")) {
      throw new McpToolException("Missing required field: daily_budget");
    }
    long budgetCents = Money.minorUnits(args.get("daily_budget").asDouble());
    String finalUrl = support.requireHttpUrl(requireText(args, "final_url"), "final_url");
    List<String> headlines = stringList(args, "headlines");
    if (headlines.size() < MIN_HEADLINES || headlines.size() > MAX_HEADLINES) {
      throw new McpToolException(
          "headlines must contain " + MIN_HEADLINES + "-" + MAX_HEADLINES + " entries.");
    }
    for (String headline : headlines) {
      if (headline.length() > MAX_HEADLINE_LENGTH) {
        throw new McpToolException(
            "Headline over "
                + MAX_HEADLINE_LENGTH
                + " characters: \""
                + headline
                + "\" ("
                + headline.length()
                + ").");
      }
    }
    List<String> descriptions = stringList(args, "descriptions");
    if (descriptions.size() < MIN_DESCRIPTIONS || descriptions.size() > MAX_DESCRIPTIONS) {
      throw new McpToolException(
          "descriptions must contain " + MIN_DESCRIPTIONS + "-" + MAX_DESCRIPTIONS + " entries.");
    }
    for (String description : descriptions) {
      if (description.length() > MAX_DESCRIPTION_LENGTH) {
        throw new McpToolException(
            "Description over "
                + MAX_DESCRIPTION_LENGTH
                + " characters: \""
                + description
                + "\" ("
                + description.length()
                + ").");
      }
    }
    List<String> keywords = stringList(args, "keywords");
    if (keywords.isEmpty()) {
      throw new McpToolException("keywords must contain at least one entry.");
    }
    if (keywords.size() > MAX_KEYWORDS) {
      throw new McpToolException("keywords must contain at most " + MAX_KEYWORDS + " entries.");
    }
    List<String> locations = optionalStringList(args, "locations");
    if (locations.size() > GoogleAdsEditSupport.MAX_LOCATIONS) {
      throw new McpToolException(
          "locations must contain at most " + GoogleAdsEditSupport.MAX_LOCATIONS + " entries.");
    }
    List<String> languages = optionalStringList(args, "languages");
    if (languages.size() > GoogleAdsEditSupport.MAX_LOCATIONS) {
      throw new McpToolException(
          "languages must contain at most " + GoogleAdsEditSupport.MAX_LOCATIONS + " entries.");
    }
    return new GoogleCreateCampaignSpec(
        name, budgetCents, finalUrl, headlines, descriptions, keywords, locations, languages);
  }

  private void rollback(
      String token, String customerId, String loginCustomerId, List<String> createdResourceNames) {
    for (int i = createdResourceNames.size() - 1; i >= 0; i--) {
      try {
        adsService
            .client()
            .removeResource(token, customerId, loginCustomerId, createdResourceNames.get(i));
      } catch (RuntimeException ex) {
        log.warn("Rollback failed for {}: {}", createdResourceNames.get(i), ex.getMessage());
      }
    }
  }

  private String requireText(JsonNode args, String field) {
    if (!args.hasNonNull(field) || args.get(field).asText().isBlank()) {
      throw new McpToolException("Missing required field: " + field);
    }
    return args.get(field).asText();
  }

  private List<String> optionalStringList(JsonNode args, String field) {
    if (!args.has(field) || args.get(field).isNull()) {
      return List.of();
    }
    return stringList(args, field);
  }

  private List<String> stringList(JsonNode args, String field) {
    if (!args.has(field) || !args.get(field).isArray()) {
      throw new McpToolException("Missing required field: " + field);
    }
    List<String> out = new ArrayList<>();
    args.get(field)
        .forEach(
            n -> {
              String value = n.asText().trim();
              if (!value.isBlank()) {
                out.add(value);
              }
            });
    return out;
  }

  // Says the uncomfortable thing out loud when no location was given: Google's default
  // is every country, which is almost never what the advertiser meant.
  private String locationSummary(List<GoogleResolvedLocation> locations) {
    if (locations.isEmpty()) {
      return "targeting ALL locations (no locations were given — narrow it with"
          + " google_update_campaign_targeting before activating)";
    }
    return "targeting "
        + String.join(", ", locations.stream().map(GoogleResolvedLocation::name).toList());
  }

  // Same uncomfortable default on the language axis: no criteria means every language,
  // so English copy would be served to somebody browsing Google in Ukrainian.
  private String languageSummary(List<GoogleResolvedLanguage> languages) {
    if (languages.isEmpty()) {
      return "in ALL languages (no languages were given)";
    }
    return "in " + String.join(", ", languages.stream().map(GoogleResolvedLanguage::name).toList());
  }

  private String lastSegment(String resourceName) {
    return resourceName.substring(resourceName.lastIndexOf('/') + 1);
  }
}
