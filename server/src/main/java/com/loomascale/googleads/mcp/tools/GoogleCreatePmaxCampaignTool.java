package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAssetLinkSpec;
import com.loomascale.googleads.client.dto.GoogleCampaignBiddingSpec;
import com.loomascale.googleads.client.dto.GoogleCreatePmaxCampaignSpec;
import com.loomascale.googleads.client.dto.GoogleCreatePmaxResult;
import com.loomascale.googleads.client.dto.GoogleMediaSlot;
import com.loomascale.googleads.client.dto.GoogleResolvedLanguage;
import com.loomascale.googleads.client.dto.GoogleResolvedLocation;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.googleads.mcp.service.GoogleAssetMediaService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.guardrail.BudgetGuardrailService;
import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.McpToolException;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Creates a complete Google Performance Max campaign (budget → campaign → targeting →
// asset group → text assets → images and video → search themes), ALWAYS PAUSED. The tool
// schema has no status field, so the model cannot make anything live — activation is a
// separate, guarded tool.
//
// Unlike google_create_campaign this needs no rollback: everything except the image and
// video assets lands in one atomic googleAds:mutate, so Google either builds the whole
// campaign or builds none of it. The assets are the exception, and deliberately so — they
// carry bytes, they are created before the atomic call so a rejected image never leaves a
// half-built campaign behind, and an Asset cannot be deleted through the Google Ads API at
// all. An asset left unlinked by a failed create is inert and content-deduplicated, so a
// corrected retry reuses it instead of piling up copies.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCreatePmaxCampaignTool implements AdsTool {

  // Google's asset group requirements live in GoogleAdsEditSupport, shared with
  // google_update_asset_group_assets so the two cannot disagree about what an asset group
  // will accept.
  private static final int MIN_HEADLINES = GoogleAdsEditSupport.PMAX_MIN_HEADLINES;
  private static final int MAX_HEADLINES = GoogleAdsEditSupport.PMAX_MAX_HEADLINES;
  private static final int MAX_HEADLINE_LENGTH = GoogleAdsEditSupport.PMAX_MAX_HEADLINE_LENGTH;
  private static final int MIN_LONG_HEADLINES = GoogleAdsEditSupport.PMAX_MIN_LONG_HEADLINES;
  private static final int MAX_LONG_HEADLINES = GoogleAdsEditSupport.PMAX_MAX_LONG_HEADLINES;
  private static final int MAX_LONG_HEADLINE_LENGTH =
      GoogleAdsEditSupport.PMAX_MAX_LONG_HEADLINE_LENGTH;
  private static final int MIN_DESCRIPTIONS = GoogleAdsEditSupport.PMAX_MIN_DESCRIPTIONS;
  private static final int MAX_DESCRIPTIONS = GoogleAdsEditSupport.PMAX_MAX_DESCRIPTIONS;
  private static final int MAX_DESCRIPTION_LENGTH =
      GoogleAdsEditSupport.PMAX_MAX_DESCRIPTION_LENGTH;
  private static final int MAX_BUSINESS_NAME_LENGTH =
      GoogleAdsEditSupport.PMAX_MAX_BUSINESS_NAME_LENGTH;

  private static final String YOUTUBE_VIDEO = "YOUTUBE_VIDEO";
  private static final int MAX_VIDEOS = 5;

  // The 11-character id from a watch URL, not the URL itself.
  private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

  // Same cap as google_create_campaign: each call permanently creates a campaign and a pile
  // of assets. A static final int, because the cross-cutting tests build this bean with null
  // dependencies and still call description().
  private static final int MAX_CREATES_PER_DAY = 10;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final GoogleAssetMediaService media;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ProductBranding branding;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_create_pmax_campaign";
  }

  @Override
  public String description() {
    return "Create a Google Ads Performance Max campaign complete enough to serve: a budget, the"
        + " campaign, one asset group with headlines, descriptions and images, optional YouTube"
        + " videos and search themes. The campaign is created PAUSED — nothing spends until you"
        + " run google_activate_campaign. Performance Max needs more copy than a Search ad: "
        + MIN_HEADLINES
        + "-"
        + MAX_HEADLINES
        + " headlines of "
        + MAX_HEADLINE_LENGTH
        + " characters, "
        + MIN_LONG_HEADLINES
        + "-"
        + MAX_LONG_HEADLINES
        + " long headlines of "
        + MAX_LONG_HEADLINE_LENGTH
        + ", "
        + MIN_DESCRIPTIONS
        + "-"
        + MAX_DESCRIPTIONS
        + " descriptions of "
        + MAX_DESCRIPTION_LENGTH
        + " with at least one under "
        + GoogleAdsEditSupport.PMAX_SHORT_DESCRIPTION_LENGTH
        + ", a business name, and images in three required slots — Google will not run an asset"
        + " group without them. It bids to maximize conversions; pass target_roas to bid on"
        + " conversion value instead. Pass locations to scope it geographically — without them the"
        + " campaign targets every location on earth, which is Google's default; use"
        + " google_find_locations to turn place names into ids. For a Search campaign use"
        + " google_create_campaign, and to change this campaign afterwards use"
        + " google_update_asset_group_assets, google_update_asset_group_media,"
        + " google_update_brand_assets and google_update_search_themes.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "name", "string", "Campaign name.");
    McpSchemas.prop(
        schema, "daily_budget", "number", McpSchemas.moneyInputDescription("Daily budget."));
    McpSchemas.prop(
        schema, "final_url", "string", "Landing page URL the asset group sends clicks to.");
    McpSchemas.stringArrayProp(
        schema,
        "headlines",
        MIN_HEADLINES
            + "-"
            + MAX_HEADLINES
            + " headlines, each "
            + MAX_HEADLINE_LENGTH
            + " characters or fewer.");
    McpSchemas.stringArrayProp(
        schema,
        "long_headlines",
        MIN_LONG_HEADLINES
            + "-"
            + MAX_LONG_HEADLINES
            + " long headlines, each "
            + MAX_LONG_HEADLINE_LENGTH
            + " characters or fewer. Performance Max shows these where there is room for a"
            + " longer line.");
    McpSchemas.stringArrayProp(
        schema,
        "descriptions",
        MIN_DESCRIPTIONS
            + "-"
            + MAX_DESCRIPTIONS
            + " descriptions, each "
            + MAX_DESCRIPTION_LENGTH
            + " characters or fewer, at least one of them "
            + GoogleAdsEditSupport.PMAX_SHORT_DESCRIPTION_LENGTH
            + " or fewer — Google refuses an asset group whose descriptions are all long.");
    McpSchemas.prop(
        schema,
        "business_name",
        "string",
        "The advertiser's name as it should appear in the ads, at most "
            + MAX_BUSINESS_NAME_LENGTH
            + " characters.");
    for (GoogleMediaSlot slot : GoogleAssetMediaService.IMAGE_SLOTS) {
      McpSchemas.stringArrayProp(
          schema,
          slot.argument(),
          "Public https image URLs for this slot: "
              + (slot.min() == 0 ? "optional, " : "at least " + slot.min() + ", ")
              + "at most "
              + slot.max()
              + ", each "
              + slot.aspectRatioLabel()
              + " and at least "
              + slot.minWidth()
              + "x"
              + slot.minHeight()
              + " pixels, PNG or JPEG under "
              + GoogleAssetMediaService.MAX_IMAGE_BYTES / 1024
              + " KB.");
    }
    McpSchemas.stringArrayProp(
        schema,
        "youtube_video_ids",
        "YouTube video ids — the 11-character id from the watch URL, not the URL. At most "
            + MAX_VIDEOS
            + ". Omit them and Google generates video from the images instead.");
    McpSchemas.stringArrayProp(
        schema,
        "search_themes",
        "Search themes telling Google what people look for when they want this, at most "
            + GoogleAdsEditSupport.MAX_SEARCH_THEMES
            + ". They steer the campaign rather than restrict it, and a new campaign with no"
            + " conversion history needs them most.");
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
            + ". A campaign with no language criteria shows to every language.");
    McpSchemas.prop(
        schema,
        "target_roas",
        "number",
        "Target return on ad spend, as a plain ratio: 4.0 asks Google for four units of"
            + " conversion value per unit of spend. Passing it switches the campaign from"
            + " maximizing conversion count to maximizing conversion value. Leave it out on a"
            + " campaign with no conversion history — a target Google cannot hit throttles"
            + " delivery to nothing.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    List<String> required = new ArrayList<>();
    required.add("name");
    required.add("daily_budget");
    required.add("final_url");
    required.add("headlines");
    required.add("long_headlines");
    required.add("descriptions");
    required.add("business_name");
    for (GoogleMediaSlot slot : GoogleAssetMediaService.IMAGE_SLOTS) {
      if (slot.min() > 0) {
        required.add(slot.argument());
      }
    }
    McpSchemas.required(schema, required.toArray(new String[0]));
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Id of the campaign that was created.");
    McpSchemas.prop(schema, "assetGroupId", "string", "Id of the asset group created inside it.");
    ObjectNode status =
        McpSchemas.prop(
            schema,
            "status",
            "string",
            "Always PAUSED — nothing spends until google_activate_campaign runs.");
    McpSchemas.enumValues(status, List.of("PAUSED"));
    ObjectNode strategy =
        McpSchemas.prop(
            schema, "biddingStrategy", "string", "Bidding strategy the campaign was created on.");
    McpSchemas.enumValues(
        strategy,
        List.of(
            GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS,
            GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE));
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(schema, "dailyBudget", "Daily budget of the new campaign.");
    McpSchemas.nullableProp(
        schema, "targetRoas", "number", "Target ROAS in effect, or null when bidding on count.");
    McpSchemas.prop(schema, "assetLinkCount", "integer", "Assets linked to the new campaign.");
    McpSchemas.prop(schema, "imageCount", "integer", "Image assets created and linked.");
    McpSchemas.prop(schema, "videoCount", "integer", "YouTube video assets linked.");
    McpSchemas.stringArrayProp(schema, "searchThemes", "Search themes the asset group carries.");
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
    McpSchemas.required(
        schema,
        "campaignId",
        "assetGroupId",
        "status",
        "biddingStrategy",
        "dailyBudget",
        "assetLinkCount",
        "imageCount",
        "videoCount");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Not destructive: it only creates, and the campaign it creates is paused, so it cannot
    // spend. Not idempotent: every call builds another campaign.
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

    GoogleCreatePmaxCampaignSpec spec = parseSpec(args);

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    // Resolved before the guardrail runs so a refusal names the account currency.
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);
    guardrail.enforce(connection, spec.dailyBudgetCents(), currency);
    // Resolved before anything is created: an unknown place name must not leave a pile of
    // assets behind, and an Asset cannot be deleted through the API.
    List<GoogleResolvedLocation> locations =
        support.resolveLocations(
            connection, token, customerId, loginCustomerId, spec.locations(), "locations");
    List<GoogleResolvedLanguage> languages =
        support.resolveLanguages(
            connection, token, customerId, loginCustomerId, spec.languages(), "languages");

    GoogleCampaignBiddingSpec bidding =
        spec.targetRoas() != null
            ? new GoogleCampaignBiddingSpec(
                GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE,
                null,
                null,
                spec.targetRoas())
            : new GoogleCampaignBiddingSpec(
                GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS, null, null, null);

    String argsSummary =
        "name="
            + spec.name()
            + ";dailyBudgetCents="
            + spec.dailyBudgetCents()
            + ";strategy="
            + bidding.strategy();
    String campaignId = null;
    try {
      // Images and videos first, on their own requests. uploadImage checks the bytes, the
      // format, the dimensions and the aspect ratio locally before it creates anything, so a
      // bad URL fails here rather than half way through building a campaign.
      List<GoogleAssetLinkSpec> assetGroupMediaLinks = new ArrayList<>();
      List<GoogleAssetLinkSpec> campaignMediaLinks = new ArrayList<>();
      int imageCount = 0;
      for (GoogleMediaSlot slot : GoogleAssetMediaService.IMAGE_SLOTS) {
        // Deduplicated by the resource name Google hands back, not by the URL: two different
        // URLs holding the same bytes come back as the one asset the account already has, and
        // two links with the same asset, field type and parent fail the whole atomic mutate.
        // Same LinkedHashSet the update tools use for this.
        Set<String> assetResourceNames = new LinkedHashSet<>();
        for (String url : spec.imageUrlsByFieldType().getOrDefault(slot.fieldType(), List.of())) {
          assetResourceNames.add(
              media.uploadImage(connection, token, customerId, loginCustomerId, slot, url));
        }
        for (String assetResourceName : assetResourceNames) {
          GoogleAssetLinkSpec link =
              new GoogleAssetLinkSpec(assetResourceName, null, slot.fieldType());
          // Brand guidelines are on for campaigns created here, so Google holds the logos on
          // the campaign rather than on the asset group.
          if (GoogleAdsEditSupport.BRAND_FIELD_TYPES.contains(slot.fieldType())) {
            campaignMediaLinks.add(link);
          } else {
            assetGroupMediaLinks.add(link);
          }
          imageCount++;
        }
      }
      for (String videoId : spec.youtubeVideoIds()) {
        String assetResourceName =
            adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .createYoutubeVideoAsset(
                            token,
                            customerId,
                            loginCustomerId,
                            branding.productName() + " video " + videoId,
                            videoId));
        assetGroupMediaLinks.add(new GoogleAssetLinkSpec(assetResourceName, null, YOUTUBE_VIDEO));
      }

      List<String> geoTargetIds =
          locations.stream().map(GoogleResolvedLocation::geoTargetId).toList();
      List<String> languageIds = languages.stream().map(GoogleResolvedLanguage::id).toList();
      List<GoogleAssetLinkSpec> groupLinks = List.copyOf(assetGroupMediaLinks);
      List<GoogleAssetLinkSpec> campaignLinks = List.copyOf(campaignMediaLinks);
      GoogleCreatePmaxResult result =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createPerformanceMaxCampaign(
                          token,
                          customerId,
                          loginCustomerId,
                          spec,
                          bidding,
                          geoTargetIds,
                          languageIds,
                          groupLinks,
                          campaignLinks));
      campaignId = lastSegment(result.campaignResourceName());
      String assetGroupId = lastSegment(result.assetGroupResourceName());
      log.debug(
          "Created PAUSED Performance Max campaign {} with asset group {} and {} asset links for"
              + " user {}",
          campaignId,
          assetGroupId,
          result.assetLinkCount(),
          userId);

      audit.record(userId, name(), WriteKind.CREATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_create_pmax_campaign</b>\nUser: "
              + userId
              + "\nCampaign: "
              + spec.name()
              + " ("
              + Money.display(spec.dailyBudgetCents(), currency)
              + "/day, PAUSED, "
              + bidding.strategy()
              + ")\nLocations: "
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
      structured.put("assetGroupId", assetGroupId);
      structured.put("status", "PAUSED");
      structured.put("biddingStrategy", bidding.strategy());
      structured.put("currency", currency);
      structured.put("dailyBudget", Money.majorUnits(spec.dailyBudgetCents()));
      if (spec.targetRoas() != null) {
        structured.put("targetRoas", spec.targetRoas());
      } else {
        structured.putNull("targetRoas");
      }
      structured.put("assetLinkCount", result.assetLinkCount());
      structured.put("imageCount", imageCount);
      structured.put("videoCount", spec.youtubeVideoIds().size());
      ArrayNode themeArr = structured.putArray("searchThemes");
      spec.searchThemes().forEach(themeArr::add);
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
          "Created Google Performance Max campaign \""
              + spec.name()
              + "\" (id "
              + campaignId
              + ") — PAUSED at "
              + Money.display(spec.dailyBudgetCents(), currency)
              + "/day, bidding "
              + biddingSummary(bidding)
              + ". Asset group "
              + assetGroupId
              + " carries "
              + spec.headlines().size()
              + " headlines, "
              + spec.longHeadlines().size()
              + " long headlines, "
              + spec.descriptions().size()
              + " descriptions, "
              + imageCount
              + " images, "
              + spec.youtubeVideoIds().size()
              + " videos and "
              + spec.searchThemes().size()
              + " search themes, "
              + locationSummary(locations)
              + ", "
              + languageSummary(languages)
              + ". The business name and logos sit on the campaign because brand guidelines are"
              + " on — google_update_brand_assets edits those. Final URL expansion is off, so"
              + " ads send clicks to "
              + spec.finalUrl()
              + " only. Review it, then run google_activate_campaign to go live.";
      return ToolResult.ok(text, structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.CREATE, argsSummary, campaignId, false, e.getMessage());
      String hint = support.assetErrorHint(e.getMessage());
      if (!hint.isEmpty() && e instanceof McpToolException) {
        throw new McpToolException(e.getMessage() + " " + hint);
      }
      throw e;
    }
  }

  private GoogleCreatePmaxCampaignSpec parseSpec(JsonNode args) {
    String name = requireText(args, "name");
    if (!args.hasNonNull("daily_budget")) {
      throw new McpToolException("Missing required field: daily_budget");
    }
    long budgetCents = Money.minorUnits(args.get("daily_budget").asDouble());
    String finalUrl = support.requireHttpUrl(requireText(args, "final_url"), "final_url");
    List<String> headlines =
        support.parseAssetTexts(
            args.get("headlines"), "headlines", MIN_HEADLINES, MAX_HEADLINES, MAX_HEADLINE_LENGTH);
    List<String> longHeadlines =
        support.parseAssetTexts(
            args.get("long_headlines"),
            "long_headlines",
            MIN_LONG_HEADLINES,
            MAX_LONG_HEADLINES,
            MAX_LONG_HEADLINE_LENGTH);
    List<String> descriptions =
        support.parseAssetTexts(
            args.get("descriptions"),
            "descriptions",
            MIN_DESCRIPTIONS,
            MAX_DESCRIPTIONS,
            MAX_DESCRIPTION_LENGTH);
    support.requireShortDescription(descriptions);
    String businessName = requireText(args, "business_name");
    if (businessName.length() > MAX_BUSINESS_NAME_LENGTH) {
      throw new McpToolException(
          "business_name is over "
              + MAX_BUSINESS_NAME_LENGTH
              + " characters: \""
              + businessName
              + "\" ("
              + businessName.length()
              + ").");
    }

    Map<String, List<String>> imageUrls = new LinkedHashMap<>();
    int totalUrls = 0;
    for (GoogleMediaSlot slot : GoogleAssetMediaService.IMAGE_SLOTS) {
      List<String> urls = parseImageUrls(args, slot);
      imageUrls.put(slot.fieldType(), urls);
      totalUrls += urls.size();
    }
    if (totalUrls > GoogleAssetMediaService.MAX_URLS_PER_CALL) {
      // One outbound fetch per URL, so this bounds bandwidth as well as blast radius. A
      // campaign that wants more images gets them through
      // google_update_asset_group_media afterwards.
      throw new McpToolException(
          "At most "
              + GoogleAssetMediaService.MAX_URLS_PER_CALL
              + " image URLs can be downloaded in one call (got "
              + totalUrls
              + "). Create the campaign with the images it needs, then add the rest with"
              + " google_update_asset_group_media.");
    }

    List<String> videoIds = parseVideoIds(args);
    List<String> searchThemes =
        args.has("search_themes") && !args.get("search_themes").isNull()
            ? support.parseSearchThemes(args.get("search_themes"), "search_themes")
            : List.of();
    List<String> locations = entries(args, "locations");
    List<String> languages = entries(args, "languages");

    Double targetRoas = null;
    if (args.hasNonNull("target_roas")) {
      targetRoas = args.get("target_roas").asDouble();
      if (targetRoas <= 0) {
        throw new McpToolException(
            "target_roas has to be greater than zero — it is a ratio, so 4.0 means four units of"
                + " conversion value per unit of spend.");
      }
    }
    log.debug(
        "Parsed Performance Max create spec for '{}': {} headlines, {} long headlines, {}"
            + " descriptions, {} image URLs, {} videos, {} search themes",
        name,
        headlines.size(),
        longHeadlines.size(),
        descriptions.size(),
        totalUrls,
        videoIds.size(),
        searchThemes.size());
    return new GoogleCreatePmaxCampaignSpec(
        name,
        budgetCents,
        finalUrl,
        headlines,
        longHeadlines,
        descriptions,
        businessName,
        imageUrls,
        videoIds,
        searchThemes,
        locations,
        languages,
        targetRoas);
  }

  // One slot's image URLs. A slot Google demands a minimum of is refused here rather than by
  // Google, because by the time Google sees it the other slots' assets already exist.
  private List<String> parseImageUrls(JsonNode args, GoogleMediaSlot slot) {
    JsonNode node = args.get(slot.argument());
    if (node == null || node.isNull()) {
      if (slot.min() > 0) {
        throw new McpToolException(
            "Missing required field: "
                + slot.argument()
                + ". Google will not run a Performance Max asset group without at least "
                + slot.min()
                + " "
                + slot.aspectRatioLabel()
                + " image.");
      }
      return List.of();
    }
    if (!node.isArray()) {
      throw new McpToolException(slot.argument() + " must be an array of https image URLs.");
    }
    List<String> urls = new ArrayList<>();
    for (JsonNode entry : node) {
      String url = support.requireHttpUrl(entry.asText("").trim(), slot.argument());
      if (!urls.contains(url)) {
        urls.add(url);
      }
    }
    if (urls.size() < slot.min() || urls.size() > slot.max()) {
      throw new McpToolException(
          slot.argument()
              + " must contain "
              + (slot.min() == 0 ? "at most " + slot.max() : slot.min() + "-" + slot.max())
              + " URLs (got "
              + urls.size()
              + ")."
              + (slot.min() > 0
                  ? " Google requires at least " + slot.min() + " for this slot."
                  : ""));
    }
    return urls;
  }

  private List<String> parseVideoIds(JsonNode args) {
    JsonNode node = args.get("youtube_video_ids");
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new McpToolException("youtube_video_ids must be an array of YouTube video ids.");
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode entry : node) {
      String id = entry.asText("").trim();
      if (!YOUTUBE_ID.matcher(id).matches()) {
        // Pasting the watch URL is the common mistake, so name it.
        throw new McpToolException(
            "\""
                + id
                + "\" is not a YouTube video id. Pass the 11-character id from the watch URL"
                + " (the v= part), not the URL itself.");
      }
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    if (ids.size() > MAX_VIDEOS) {
      throw new McpToolException(
          "A new asset group takes at most "
              + MAX_VIDEOS
              + " videos (got "
              + ids.size()
              + "). Add more with google_update_asset_group_media.");
    }
    return ids;
  }

  private String biddingSummary(GoogleCampaignBiddingSpec bidding) {
    if (bidding.targetRoas() != null) {
      return "for conversion value at a target ROAS of " + bidding.targetRoas();
    }
    return "to maximize conversions with no target — set one with"
        + " google_update_campaign_bidding once it has conversion history";
  }

  // Says the uncomfortable thing out loud when no location was given: Google's default is
  // every country, which is almost never what the advertiser meant.
  private String locationSummary(List<GoogleResolvedLocation> locations) {
    if (locations.isEmpty()) {
      return "targeting ALL locations (no locations were given — narrow it with"
          + " google_update_campaign_targeting before activating)";
    }
    return "targeting "
        + String.join(", ", locations.stream().map(GoogleResolvedLocation::name).toList());
  }

  // Same uncomfortable default on the language axis: no criteria means every language.
  private String languageSummary(List<GoogleResolvedLanguage> languages) {
    if (languages.isEmpty()) {
      return "in ALL languages (no languages were given)";
    }
    return "in " + String.join(", ", languages.stream().map(GoogleResolvedLanguage::name).toList());
  }

  // Raw targeting entries as given: a geo target id or a place name, a language id, an ISO
  // code or a language name. resolveLocations and resolveLanguages cap the count and turn
  // them into ids.
  private List<String> entries(JsonNode args, String field) {
    if (!args.has(field) || args.get(field).isNull()) {
      return List.of();
    }
    if (!args.get(field).isArray()) {
      throw new McpToolException(field + " must be an array.");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode entry : args.get(field)) {
      String value = entry.asText("").trim();
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return values;
  }

  private String requireText(JsonNode args, String field) {
    if (!args.hasNonNull(field) || args.get(field).asText().isBlank()) {
      throw new McpToolException("Missing required field: " + field);
    }
    return args.get(field).asText().trim();
  }

  private String lastSegment(String resourceName) {
    return resourceName.substring(resourceName.lastIndexOf('/') + 1);
  }
}
