package com.loomascale.googleads.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleAdTextAssetDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDetailDto;
import com.loomascale.googleads.client.dto.GoogleBrandDto;
import com.loomascale.googleads.client.dto.GoogleBrandSuggestionDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleGeoTargetSuggestionDto;
import com.loomascale.googleads.client.dto.GoogleKeywordCreateSpec;
import com.loomascale.googleads.client.dto.GoogleKeywordDto;
import com.loomascale.googleads.client.dto.GoogleLanguageConstantDto;
import com.loomascale.googleads.client.dto.GoogleNegativeKeywordSpec;
import com.loomascale.googleads.client.dto.GoogleResolvedLanguage;
import com.loomascale.googleads.client.dto.GoogleResolvedLocation;
import com.loomascale.googleads.client.dto.GoogleSitelinkLevel;
import com.loomascale.googleads.client.dto.GoogleSitelinkSpec;
import com.loomascale.googleads.client.dto.GoogleSitelinkUpdateSpec;
import com.loomascale.mcp.money.Money;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.tool.McpToolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Lookups and guardrails shared by the Google keyword and ad group edit tools. Each
// resolve* call doubles as an ownership check: an id that does not belong to the
// selected customer simply is not in the listing, so the tool refuses instead of
// mutating a stranger's account.
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAdsEditSupport {

  // Google only honours manual bids under MANUAL_CPC; every automated strategy
  // computes its own bid and ignores what the advertiser set.
  public static final String MANUAL_CPC = "MANUAL_CPC";

  public static final List<String> MATCH_TYPES = List.of("EXACT", "PHRASE", "BROAD");

  // Google accepts thousands of location criteria per campaign; this cap is about blast
  // radius and readable results, not the API limit.
  public static final int MAX_LOCATIONS = 20;

  public static final List<String> GEO_TARGET_TYPES = List.of("PRESENCE", "PRESENCE_OR_INTEREST");

  // Google's responsive-search-ad limits, shared by the tool that creates an ad and the
  // one that rewrites an existing one, so the two cannot drift into disagreeing about
  // what Google accepts.
  public static final int MIN_HEADLINES = 3;
  public static final int MAX_HEADLINES = 15;
  public static final int MAX_HEADLINE_LENGTH = 30;
  public static final int MIN_DESCRIPTIONS = 2;
  public static final int MAX_DESCRIPTIONS = 4;
  public static final int MAX_DESCRIPTION_LENGTH = 90;
  public static final int MAX_PATH_LENGTH = 15;

  // Google allows three enabled responsive search ads per ad group and refuses the fourth
  // with an error that names no limit. Checking it before the mutate is the difference
  // between "pause one of these three first" and an opaque Google failure.
  public static final int MAX_ENABLED_RSAS_PER_AD_GROUP = 3;

  // Slots an RSA asset may be pinned to. Pinning holds an asset in one position instead
  // of letting Google rotate it.
  public static final List<String> HEADLINE_PINS =
      List.of("HEADLINE_1", "HEADLINE_2", "HEADLINE_3");

  public static final List<String> DESCRIPTION_PINS = List.of("DESCRIPTION_1", "DESCRIPTION_2");

  // Performance Max asset-group limits. Prefixed rather than reusing the RSA block above,
  // because PMax and an RSA agree on headlines and disagree on descriptions — 5 against an
  // RSA's 4 — and PMax adds long headlines and a business name an RSA has no concept of.
  // Sharing MAX_DESCRIPTIONS would have been a silent off-by-one in one direction or the
  // other.
  public static final int PMAX_MIN_HEADLINES = 3;
  public static final int PMAX_MAX_HEADLINES = 15;
  public static final int PMAX_MAX_HEADLINE_LENGTH = 30;
  public static final int PMAX_MIN_LONG_HEADLINES = 1;
  public static final int PMAX_MAX_LONG_HEADLINES = 5;
  public static final int PMAX_MAX_LONG_HEADLINE_LENGTH = 90;
  public static final int PMAX_MIN_DESCRIPTIONS = 2;
  public static final int PMAX_MAX_DESCRIPTIONS = 5;
  public static final int PMAX_MAX_DESCRIPTION_LENGTH = 90;

  // Google refuses an asset group whose every description is long, with
  // SHORT_DESCRIPTION_REQUIRED: at least one has to fit in this.
  public static final int PMAX_SHORT_DESCRIPTION_LENGTH = 60;

  public static final int PMAX_MAX_BUSINESS_NAME_LENGTH = 25;

  // Search themes per asset group. Google's published cap is 25 and a raise to 50 is
  // rolling out; enforcing the lower number never trips the API, at the cost of refusing
  // 26-50 on an account that already has the raise. One line to change when it completes.
  public static final int MAX_SEARCH_THEMES = 25;
  public static final int MAX_SEARCH_THEME_LENGTH = 80;

  public static final String PERFORMANCE_MAX = "PERFORMANCE_MAX";

  // Google accepts 10,000 negative keywords on a Performance Max campaign; this cap is
  // about blast radius and a readable result, not the API limit — the same framing as
  // MAX_LOCATIONS.
  public static final int MAX_NEGATIVE_KEYWORDS = 50;

  // One suggestBrands call per brand name, so this cap bounds round trips as well as blast
  // radius.
  public static final int MAX_BRANDS = 10;

  // Google's own keyword limits, refused here so one bad entry does not fail the batch.
  public static final int MAX_KEYWORD_LENGTH = 80;
  public static final int MAX_KEYWORD_WORDS = 10;

  // Brand exclusions apply to these channels only.
  public static final List<String> BRAND_EXCLUSION_CHANNELS = List.of(PERFORMANCE_MAX, "SEARCH");

  // Field types Google moves from the asset group to the campaign once brand guidelines
  // are enabled, which is the default for Performance Max campaigns created since v21.
  public static final List<String> BRAND_FIELD_TYPES =
      List.of("BUSINESS_NAME", "LOGO", "LANDSCAPE_LOGO");

  // Google's sitelink limits. Link text is the blue line a searcher clicks; the two
  // descriptions are the lines under it, and Google refuses one without the other.
  public static final int SITELINK_MAX_LINK_TEXT_LENGTH = 25;
  public static final int SITELINK_MAX_DESCRIPTION_LENGTH = 35;

  // Google serves at most 20 sitelinks per level. Enforced here rather than by the API for
  // the same reason parseAssetTexts counts before it creates: creating the assets and
  // linking them are two requests, and an Asset can never be deleted, so a count Google
  // rejects on the second request leaves the first request's assets in the account for good.
  public static final int MAX_SITELINKS_PER_LEVEL = 20;
  public static final int MAX_SITELINKS_PER_CALL = 20;

  private static final Pattern GEO_TARGET_ID = Pattern.compile("\\d+");
  public static final List<String> STATUSES = List.of("ENABLED", "PAUSED");

  private final GoogleAdsService adsService;

  public GoogleAdGroupDto requireAdGroup(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String adGroupId) {
    return adsService
        .call(
            connection,
            () -> adsService.client().listAdGroups(token, customerId, loginCustomerId, null))
        .stream()
        .filter(adGroup -> adGroupId.equals(adGroup.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new McpToolException(
                    "Ad group "
                        + adGroupId
                        + " was not found in account "
                        + customerId
                        + ". Use google_list_campaigns with include_ad_groups to see ad groups."));
  }

  public GoogleCampaignDto requireCampaign(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String campaignId) {
    return adsService
        .call(
            connection, () -> adsService.client().listCampaigns(token, customerId, loginCustomerId))
        .stream()
        .filter(campaign -> campaignId.equals(campaign.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new McpToolException(
                    "Campaign "
                        + campaignId
                        + " was not found in account "
                        + customerId
                        + ". Use google_list_campaigns to see campaigns."));
  }

  public List<GoogleKeywordDto> keywordsOf(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String adGroupId) {
    return adsService.call(
        connection,
        () ->
            adsService.client().listKeywords(token, customerId, loginCustomerId, null, adGroupId));
  }

  public GoogleKeywordDto requireKeyword(
      List<GoogleKeywordDto> keywords, String criterionId, String adGroupId) {
    Optional<GoogleKeywordDto> keyword =
        keywords.stream().filter(k -> criterionId.equals(k.criterionId())).findFirst();
    return keyword.orElseThrow(
        () ->
            new McpToolException(
                "Keyword criterion "
                    + criterionId
                    + " was not found in ad group "
                    + adGroupId
                    + ". Use google_list_keywords to see criterion ids."));
  }

  // A CPC bid must be positive and must not exceed the whole daily budget of the
  // campaign that pays for it — a single click cannot be allowed to cost a full day.
  // A budget we cannot read (shared budget) means we cannot check that, so refuse
  // rather than write a bid nobody validated.
  public long requireSaneBidCents(double bid, GoogleCampaignDto campaign, String currency) {
    long bidCents = Money.minorUnits(bid);
    if (bidCents <= 0) {
      throw new McpToolException("cpc_bid must be greater than zero.");
    }
    if (campaign.dailyBudgetCents() == null || campaign.dailyBudgetCents() <= 0) {
      throw new McpToolException(
          "Could not read the daily budget of campaign "
              + campaign.id()
              + " (it may use a shared budget), so the bid cannot be sanity-checked here. Set it"
              + " in Google Ads directly.");
    }
    if (bidCents > campaign.dailyBudgetCents()) {
      throw new McpToolException(
          "A CPC bid of "
              + Money.display(bidCents, currency)
              + " is higher than the whole "
              + Money.display(campaign.dailyBudgetCents(), currency)
              + "/day budget of campaign \""
              + campaign.name()
              + "\", so one click could spend the entire day. Lower the bid or raise the budget"
              + " with google_update_budget first.");
    }
    log.debug(
        "CPC bid {} cents accepted against campaign {} daily budget {} cents",
        bidCents,
        campaign.id(),
        campaign.dailyBudgetCents());
    return bidCents;
  }

  // Shared parsing of the keywords[] array accepted by google_add_keywords and
  // google_create_ad_group: text plus optional match type, bid, and negativity, with
  // one status for the whole batch. Every bid is sanity-checked against the campaign
  // budget here, so a bad bid is refused before any mutate call runs.
  public List<GoogleKeywordCreateSpec> parseKeywordSpecs(
      JsonNode keywords,
      String status,
      GoogleCampaignDto campaign,
      int maxKeywords,
      String currency) {
    if (keywords == null || !keywords.isArray() || keywords.isEmpty()) {
      throw new McpToolException("keywords must contain at least one entry.");
    }
    if (keywords.size() > maxKeywords) {
      throw new McpToolException("keywords must contain at most " + maxKeywords + " entries.");
    }
    List<GoogleKeywordCreateSpec> specs = new ArrayList<>();
    for (JsonNode entry : keywords) {
      String text = entry.path("text").asText("").trim();
      if (text.isBlank()) {
        throw new McpToolException("Every keyword needs a non-empty text.");
      }
      String matchType =
          entry.hasNonNull("match_type") ? entry.get("match_type").asText().toUpperCase() : "BROAD";
      if (!MATCH_TYPES.contains(matchType)) {
        throw new McpToolException("match_type must be one of: " + String.join(", ", MATCH_TYPES));
      }
      boolean negative = entry.path("negative").asBoolean(false);
      Long cpcBidMicros = null;
      if (entry.hasNonNull("cpc_bid")) {
        if (negative) {
          throw new McpToolException(
              "Negative keyword \"" + text + "\" cannot carry a cpc_bid — it never gets clicked.");
        }
        cpcBidMicros =
            GoogleAdsApiClient.centsToMicros(
                requireSaneBidCents(entry.get("cpc_bid").asDouble(), campaign, currency));
      }
      specs.add(new GoogleKeywordCreateSpec(text, matchType, negative, cpcBidMicros, status));
    }
    return specs;
  }

  // Headlines and descriptions as a tool receives them: each entry is either a plain
  // string or an object with a text and an optional pin. Counts and lengths are Google's,
  // and a pin outside the slots the ad has is refused here rather than by the API.
  public List<GoogleAdTextAssetDto> parseAdAssets(
      JsonNode assets, String field, int min, int max, int maxLength, List<String> pins) {
    if (assets == null || !assets.isArray()) {
      throw new McpToolException(field + " must be an array.");
    }
    List<GoogleAdTextAssetDto> parsed = new ArrayList<>();
    for (JsonNode entry : assets) {
      String text = (entry.isObject() ? entry.path("text").asText("") : entry.asText("")).trim();
      if (text.isBlank()) {
        throw new McpToolException("Every entry of " + field + " needs a non-empty text.");
      }
      if (text.length() > maxLength) {
        throw new McpToolException(
            field
                + " entry over "
                + maxLength
                + " characters: \""
                + text
                + "\" ("
                + text.length()
                + ").");
      }
      String pinned =
          entry.isObject() && entry.hasNonNull("pinned")
              ? entry.get("pinned").asText().trim().toUpperCase()
              : null;
      if (pinned != null && !pins.contains(pinned)) {
        throw new McpToolException(
            "pinned must be one of: " + String.join(", ", pins) + " (got " + pinned + ").");
      }
      parsed.add(new GoogleAdTextAssetDto(text, pinned));
    }
    if (parsed.size() < min || parsed.size() > max) {
      throw new McpToolException(field + " must contain " + min + "-" + max + " entries.");
    }
    log.debug("Parsed {} entries for {}", parsed.size(), field);
    return parsed;
  }

  // Resolving the asset group doubles as the ownership check: findAssetGroup filters by the
  // selected customer, so an id from another account is simply absent and the edit is
  // refused before any mutate. Deliberately not built on listAssetGroups — see
  // GoogleAssetGroupDetailDto for why a metrics query cannot answer this.
  public GoogleAssetGroupDetailDto requireAssetGroup(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String assetGroupId) {
    return adsService
        .call(
            connection,
            () ->
                adsService
                    .client()
                    .findAssetGroup(token, customerId, loginCustomerId, assetGroupId))
        .orElseThrow(
            () ->
                new McpToolException(
                    "Asset group "
                        + assetGroupId
                        + " was not found in account "
                        + customerId
                        + ". Use google_list_asset_groups to see asset group ids."));
  }

  // Two refusals that would otherwise arrive as opaque Google errors after a mutate has
  // already created assets nothing can delete.
  public void requireAssetGroupIsEditable(
      GoogleAssetGroupDetailDto group, Collection<String> fieldTypes) {
    if (!PERFORMANCE_MAX.equals(group.campaignChannelType())) {
      throw new McpToolException(
          "Asset group "
              + group.id()
              + " belongs to campaign \""
              + group.campaignName()
              + "\", which is a "
              + group.campaignChannelType()
              + " campaign. Asset groups only exist on Performance Max campaigns; for a"
              + " search campaign use google_update_ad and google_update_ad_group.");
    }
    if (!group.brandGuidelinesEnabled() || fieldTypes == null) {
      return;
    }
    List<String> blocked =
        fieldTypes.stream().filter(BRAND_FIELD_TYPES::contains).sorted().toList();
    if (blocked.isEmpty()) {
      return;
    }
    List<String> editable =
        fieldTypes.stream().filter(type -> !BRAND_FIELD_TYPES.contains(type)).sorted().toList();
    throw new McpToolException(
        "Campaign \""
            + group.campaignName()
            + "\" has brand guidelines enabled, so Google holds "
            + String.join(", ", blocked)
            + " on the campaign rather than on each asset group, and they cannot be changed"
            + " here. Use google_update_brand_assets for those."
            + (editable.isEmpty()
                ? ""
                : " The rest of this call ("
                    + String.join(", ", editable)
                    + ") is fine on its own."));
  }

  // One field type's complete new text list as a tool receives it: each entry is a plain
  // string or an object with a text — the shape google_update_ad already accepts, minus
  // pins, because a Performance Max asset is never pinned.
  //
  // Counts, lengths and duplicates are all refused here rather than by Google, and not out
  // of caution: creating the assets and linking them are two separate requests, and an
  // Asset cannot be deleted through the API. A count Google rejects on the second request
  // leaves the assets from the first orphaned in the customer's account for good.
  public List<String> parseAssetTexts(
      JsonNode assets, String field, int min, int max, int maxLength) {
    if (assets == null || !assets.isArray()) {
      throw new McpToolException(field + " must be an array.");
    }
    List<String> parsed = new ArrayList<>();
    for (JsonNode entry : assets) {
      String text = (entry.isObject() ? entry.path("text").asText("") : entry.asText("")).trim();
      if (text.isBlank()) {
        throw new McpToolException("Every entry of " + field + " needs a non-empty text.");
      }
      if (text.length() > maxLength) {
        throw new McpToolException(
            field
                + " entry over "
                + maxLength
                + " characters: \""
                + text
                + "\" ("
                + text.length()
                + ").");
      }
      if (parsed.contains(text)) {
        // Google Ads holds one asset per distinct text, so the same text cannot be linked
        // to a slot twice. Silently de-duplicating would make the reported count disagree
        // with what was asked for.
        throw new McpToolException(
            field + " contains \"" + text + "\" twice. Each entry has to be different.");
      }
      parsed.add(text);
    }
    if (parsed.size() < min || parsed.size() > max) {
      throw new McpToolException(
          field + " must contain " + min + "-" + max + " entries (got " + parsed.size() + ").");
    }
    log.debug("Parsed {} entries for {}", parsed.size(), field);
    return parsed;
  }

  // Google's SHORT_DESCRIPTION_REQUIRED, pre-empted: an asset group needs one description
  // short enough for the placements that only have room for a short one.
  public void requireShortDescription(List<String> descriptions) {
    boolean hasShort =
        descriptions.stream().anyMatch(text -> text.length() <= PMAX_SHORT_DESCRIPTION_LENGTH);
    if (hasShort) {
      return;
    }
    // Name the shortest entry and the exact cut it needs: a caller that only hears "shorten
    // one of them" has to guess which, and guesses another list that is still all long.
    String shortest = descriptions.stream().min(Comparator.comparingInt(String::length)).orElse("");
    StringBuilder lengths = new StringBuilder();
    for (String text : descriptions) {
      if (lengths.length() > 0) {
        lengths.append(", ");
      }
      lengths.append(text.length());
    }
    throw new McpToolException(
        "At least one description has to be "
            + PMAX_SHORT_DESCRIPTION_LENGTH
            + " characters or fewer — Google refuses an asset group whose descriptions are"
            + " all long. Yours are "
            + lengths
            + " characters. The shortest is \""
            + shortest
            + "\"; cut "
            + (shortest.length() - PMAX_SHORT_DESCRIPTION_LENGTH)
            + " characters from it (or add a short description) and send the whole list"
            + " again.");
  }

  // The search-theme list of google_update_search_themes. Not a client-side sanitizer like
  // sanitizeKeyword: a theme is a JSON string value and never reaches GAQL or a resource
  // path, so there is nothing to inject — the risk is Google rejecting the whole atomic
  // batch over one bad entry.
  public List<String> parseSearchThemes(JsonNode themes, String field) {
    if (themes == null || !themes.isArray()) {
      throw new McpToolException(
          field
              + " must be an array of search themes. Pass an empty array to remove every"
              + " search theme from the asset group.");
    }
    List<String> parsed = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode entry : themes) {
      String theme = entry.asText("").trim().replaceAll("\\s+", " ");
      if (theme.isBlank()) {
        throw new McpToolException("Every entry of " + field + " needs a non-empty theme.");
      }
      // Ordinary whitespace has already been collapsed above, so what is left here is the
      // invisible kind — zero-width joiners, bidi marks, a stray NUL from a bad paste.
      // Google rejects those, and a user cannot see why, so name the theme.
      if (theme.codePoints().anyMatch(GoogleAdsEditSupport::isControlCodePoint)) {
        throw new McpToolException(
            "Search theme \""
                + theme
                + "\" contains an invisible control character, which Google rejects. Retype it"
                + " rather than pasting it.");
      }
      // Google counts characters, and String.length() counts UTF-16 units: a theme of 45
      // emoji is 45 characters and 90 units, so the naive check would refuse a legal theme.
      int characters = theme.codePointCount(0, theme.length());
      if (characters > MAX_SEARCH_THEME_LENGTH) {
        throw new McpToolException(
            "Search theme over "
                + MAX_SEARCH_THEME_LENGTH
                + " characters: \""
                + theme
                + "\" ("
                + characters
                + ").");
      }
      // Google matches themes case-insensitively, so two spellings are one signal.
      if (seen.add(theme.toLowerCase())) {
        parsed.add(theme);
      }
    }
    if (parsed.size() > MAX_SEARCH_THEMES) {
      throw new McpToolException(
          "An asset group takes at most "
              + MAX_SEARCH_THEMES
              + " search themes (got "
              + parsed.size()
              + "). Keep the ones that describe demand you actually want.");
    }
    log.debug("Parsed {} search themes for {}", parsed.size(), field);
    return parsed;
  }

  private static boolean isControlCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.CONTROL || type == Character.FORMAT;
  }

  // Google's asset errors name a code and nothing actionable, and GoogleAdsService.call
  // passes the message straight through. This adds the one sentence that says what to do,
  // for the codes a Performance Max asset edit can actually provoke. Empty when there is
  // nothing to add.
  public String assetErrorHint(String googleMessage) {
    if (googleMessage == null) {
      return "";
    }
    if (googleMessage.contains("SHORT_DESCRIPTION_REQUIRED")) {
      return "One description has to be " + PMAX_SHORT_DESCRIPTION_LENGTH + " characters or fewer.";
    }
    if (googleMessage.contains("NOT_ENOUGH")) {
      return "The asset group would drop below what Google requires: "
          + PMAX_MIN_HEADLINES
          + " headlines, "
          + PMAX_MIN_LONG_HEADLINES
          + " long headline and "
          + PMAX_MIN_DESCRIPTIONS
          + " descriptions at minimum. Send the full list you want, including the entries"
          + " you are keeping.";
    }
    if (googleMessage.contains("ASPECT_RATIO_NOT_ALLOWED")
        || googleMessage.contains("DIMENSIONS_NOT_ALLOWED")) {
      return "Google rejected an image's shape: marketing images are 1.91:1, square images"
          + " 1:1, portrait images 4:5, logos 1:1 and landscape logos 4:1.";
    }
    if (googleMessage.contains("BRAND_ASSETS_NOT_LINKED_AT_CAMPAIGN_LEVEL")
        || googleMessage.contains("REQUIRED_LOGO_ASSET_NOT_LINKED")
        || googleMessage.contains("REQUIRED_BUSINESS_NAME_ASSET_NOT_LINKED")) {
      return "This campaign has brand guidelines enabled, so the business name and logos live"
          + " on the campaign. Use google_update_brand_assets.";
    }
    if (googleMessage.contains("TOO_MANY") || googleMessage.contains("RESOURCE_LIMIT")) {
      return "The asset group is at Google's limit for that slot. Remove an entry from the"
          + " list before adding another.";
    }
    if (googleMessage.contains("TOO_MANY_SITELINKS")) {
      return "Google serves at most "
          + MAX_SITELINKS_PER_LEVEL
          + " sitelinks per account, campaign or ad group. Unlink one with"
          + " google_remove_sitelinks before adding another.";
    }
    if (googleMessage.contains("CANNOT_MODIFY_ASSET_SOURCE")
        || googleMessage.contains("IMMUTABLE_FIELD")) {
      return "Google will not edit that asset in place. Unlink it with google_remove_sitelinks"
          + " and create the replacement with google_create_sitelinks — the new sitelink starts"
          + " with no performance history.";
    }
    if (googleMessage.contains("DUPLICATE")) {
      return "Google Ads holds one asset per distinct text, so the same entry cannot be"
          + " linked to a slot twice.";
    }
    if (googleMessage.contains("CANNOT_REMOVE")) {
      return "Google will not remove that asset, usually because the asset group needs it to"
          + " keep serving. Add the replacement in the same call.";
    }
    return "";
  }

  // The keywords[] array of google_update_campaign_negatives: text plus an optional match
  // type, and nothing else.
  //
  // Deliberately not parseKeywordSpecs: that one accepts a cpc_bid, which is meaningless on a
  // campaign negative and drags in a campaign-budget lookup that would refuse shared-budget
  // campaigns for no reason, and it accepts negative:false, which would let a caller express
  // a POSITIVE campaign-level keyword — a thing Performance Max rejects and nothing here
  // should be able to say.
  public List<GoogleNegativeKeywordSpec> parseNegativeKeywordSpecs(JsonNode keywords, int max) {
    if (keywords == null || !keywords.isArray()) {
      throw new McpToolException("negative_keywords must be an array.");
    }
    List<GoogleNegativeKeywordSpec> specs = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode entry : keywords) {
      String text = (entry.isObject() ? entry.path("text").asText("") : entry.asText("")).trim();
      if (text.isBlank()) {
        throw new McpToolException("Every entry of negative_keywords needs a non-empty text.");
      }
      if (text.length() > MAX_KEYWORD_LENGTH) {
        throw new McpToolException(
            "Negative keyword over "
                + MAX_KEYWORD_LENGTH
                + " characters: \""
                + text
                + "\" ("
                + text.length()
                + ").");
      }
      if (text.split("\\s+").length > MAX_KEYWORD_WORDS) {
        throw new McpToolException(
            "Negative keyword \""
                + text
                + "\" has more than "
                + MAX_KEYWORD_WORDS
                + " words,"
                + " which Google rejects. Exclude a shorter phrase instead.");
      }
      String matchType =
          entry.isObject() && entry.hasNonNull("match_type")
              ? entry.get("match_type").asText().trim().toUpperCase()
              : "BROAD";
      if (!MATCH_TYPES.contains(matchType)) {
        throw new McpToolException(
            "match_type must be one of: "
                + String.join(", ", MATCH_TYPES)
                + " (got "
                + matchType
                + ").");
      }
      // Same text at two match types is two real criteria, so the key is the pair.
      if (seen.add(text.toLowerCase() + "\u0000" + matchType)) {
        specs.add(new GoogleNegativeKeywordSpec(text, matchType));
      }
    }
    if (specs.isEmpty()) {
      throw new McpToolException("negative_keywords was empty — there is nothing to add.");
    }
    if (specs.size() > max) {
      throw new McpToolException(
          "At most " + max + " negative keywords per call (got " + specs.size() + ").");
    }
    log.debug("Parsed {} campaign negative keywords", specs.size());
    return specs;
  }

  // Turns the brand names a tool was given into Knowledge Graph entity ids. An entry that
  // already looks like a machine id ("/m/…", "/g/…") passes through untouched; anything else
  // goes through suggestBrands, whose exact case-insensitive name match wins. Nothing
  // matching is refused with Google's own suggestions listed, so the retry is obvious —
  // the same contract resolveLocations follows.
  public List<GoogleBrandDto> resolveBrands(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      JsonNode brands) {
    if (brands == null || !brands.isArray()) {
      throw new McpToolException("brands must be an array of brand names.");
    }
    List<GoogleBrandDto> resolved = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode entry : brands) {
      String value = entry.asText("").trim();
      if (value.isBlank()) {
        throw new McpToolException("Every entry of brands needs a non-empty name.");
      }
      if (value.startsWith("/m/") || value.startsWith("/g/")) {
        if (seen.add(value)) {
          resolved.add(new GoogleBrandDto(null, value, value, null));
        }
        continue;
      }
      List<GoogleBrandSuggestionDto> suggestions =
          adsService.call(
              connection,
              () -> adsService.client().suggestBrands(token, customerId, loginCustomerId, value));
      GoogleBrandSuggestionDto match =
          suggestions.stream()
              .filter(suggestion -> value.equalsIgnoreCase(suggestion.name()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new McpToolException(
                          "Google does not recognize a brand called \""
                              + value
                              + "\"."
                              + (suggestions.isEmpty()
                                  ? " It has no suggestions for that name."
                                  : " Did you mean: "
                                      + suggestions.stream()
                                          .map(GoogleBrandSuggestionDto::name)
                                          .limit(5)
                                          .collect(java.util.stream.Collectors.joining(", "))
                                      + "?")));
      if (seen.add(match.entityId())) {
        resolved.add(
            new GoogleBrandDto(
                null,
                match.entityId(),
                match.name(),
                match.urls().isEmpty() ? null : match.urls().get(0)));
      }
    }
    if (resolved.isEmpty()) {
      throw new McpToolException("brands was empty — there is nothing to exclude.");
    }
    if (resolved.size() > MAX_BRANDS) {
      throw new McpToolException(
          "At most " + MAX_BRANDS + " brands per call (got " + resolved.size() + ").");
    }
    log.debug("Resolved {} brands", resolved.size());
    return resolved;
  }

  // The sitelinks[] array of google_create_sitelinks: the text a searcher sees plus the page
  // the click lands on. Nothing is accepted that Google would reject, because a rejected
  // second request would strand the assets the first one created.
  public List<GoogleSitelinkSpec> parseSitelinkSpecs(JsonNode sitelinks, String field, int max) {
    if (sitelinks == null || !sitelinks.isArray() || sitelinks.isEmpty()) {
      throw new McpToolException(field + " must be a non-empty array of sitelinks.");
    }
    if (sitelinks.size() > max) {
      throw new McpToolException(
          "At most " + max + " sitelinks per call (got " + sitelinks.size() + ").");
    }
    List<GoogleSitelinkSpec> parsed = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode entry : sitelinks) {
      if (!entry.isObject()) {
        throw new McpToolException(
            "Every entry of " + field + " must be an object with link_text and final_url.");
      }
      String linkText = requireSitelinkLinkText(entry.path("link_text").asText(""), field);
      if (!seen.add(linkText.toLowerCase())) {
        // Sitelink assets are NOT deduplicated by content the way text assets are, so two
        // identical entries would become two permanent assets rather than one.
        throw new McpToolException(
            field + " contains \"" + linkText + "\" twice. Each sitelink has to be different.");
      }
      String description1 = trimmedOrNull(entry.path("description1"));
      String description2 = trimmedOrNull(entry.path("description2"));
      requireSitelinkDescriptions(linkText, description1, description2);
      parsed.add(
          new GoogleSitelinkSpec(
              linkText,
              description1,
              description2,
              List.of(
                  requireHttpsUrl(trimmedOrNull(entry.path("final_url")), field + " final_url")),
              List.of()));
    }
    log.debug("Parsed {} sitelinks for {}", parsed.size(), field);
    return parsed;
  }

  // The edit of one existing sitelink. Null means "leave this alone", so an empty spec is
  // refused here rather than becoming an empty update mask Google rejects.
  public GoogleSitelinkUpdateSpec parseSitelinkUpdateSpec(JsonNode args) {
    String linkText = trimmedOrNull(args.path("link_text"));
    if (linkText != null) {
      requireSitelinkLinkText(linkText, "link_text");
    }
    String description1 = trimmedOrNull(args.path("description1"));
    String description2 = trimmedOrNull(args.path("description2"));
    requireSitelinkDescriptionLength(description1, "description1");
    requireSitelinkDescriptionLength(description2, "description2");
    String finalUrl = trimmedOrNull(args.path("final_url"));
    GoogleSitelinkUpdateSpec spec =
        new GoogleSitelinkUpdateSpec(
            linkText,
            description1,
            description2,
            finalUrl == null ? null : List.of(requireHttpsUrl(finalUrl, "final_url")),
            null);
    if (spec.isEmpty()) {
      throw new McpToolException(
          "Nothing to change. Pass link_text, description1, description2 or final_url.");
    }
    return spec;
  }

  public String requireSitelinkLinkText(String linkText, String field) {
    String trimmed = linkText == null ? "" : linkText.trim();
    if (trimmed.isBlank()) {
      throw new McpToolException("Every entry of " + field + " needs a non-empty link_text.");
    }
    if (trimmed.length() > SITELINK_MAX_LINK_TEXT_LENGTH) {
      throw new McpToolException(
          "link_text must be at most "
              + SITELINK_MAX_LINK_TEXT_LENGTH
              + " characters: \""
              + trimmed
              + "\" ("
              + trimmed.length()
              + ").");
    }
    return trimmed;
  }

  // Google's SITELINK_DESCRIPTIONS_MUST_BE_SET_TOGETHER, pre-empted: a sitelink shows both
  // description lines or neither.
  public void requireSitelinkDescriptions(
      String linkText, String description1, String description2) {
    if ((description1 == null) != (description2 == null)) {
      throw new McpToolException(
          "Sitelink \""
              + linkText
              + "\" needs both description1 and description2 or neither — Google shows the two"
              + " lines together.");
    }
    requireSitelinkDescriptionLength(description1, "description1");
    requireSitelinkDescriptionLength(description2, "description2");
  }

  private void requireSitelinkDescriptionLength(String description, String field) {
    if (description != null && description.length() > SITELINK_MAX_DESCRIPTION_LENGTH) {
      throw new McpToolException(
          field
              + " must be at most "
              + SITELINK_MAX_DESCRIPTION_LENGTH
              + " characters: \""
              + description
              + "\" ("
              + description.length()
              + ").");
    }
  }

  // How many more sitelinks fit at this level. Checked before the first request, because the
  // create is two requests and the assets from the first survive a rejection of the second.
  public void requireSitelinkHeadroom(String level, int existing, int adding) {
    if (existing + adding > MAX_SITELINKS_PER_LEVEL) {
      throw new McpToolException(
          "Google allows "
              + MAX_SITELINKS_PER_LEVEL
              + " sitelinks per "
              + level
              + " and this one already has "
              + existing
              + ", so "
              + adding
              + " more would not fit. Remove some with google_remove_sitelinks first.");
    }
  }

  private static String trimmedOrNull(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String text = node.asText("").trim();
    return text.isEmpty() ? null : text;
  }

  // Landing pages of a sitelink specifically. Deliberately not a tightening of
  // requireHttpUrl, which other tools rely on to accept http:// URLs.
  public String requireHttpsUrl(String url, String field) {
    if (url == null || url.isBlank()) {
      throw new McpToolException(field + " is required and must be an https URL.");
    }
    if (!url.startsWith("https://")) {
      throw new McpToolException(
          field
              + " must be an https URL (got \""
              + url
              + "\"). Google refuses insecure"
              + " landing pages on new assets.");
    }
    return url;
  }

  // Which entity a sitelink write acts on, and the ownership check that goes with it: an id
  // that does not belong to the selected customer is not in the listing, so the tool refuses
  // instead of mutating a stranger's account. Account level owns nothing narrower, so there
  // is no id to verify and none is accepted.
  public String resolveSitelinkOwner(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      GoogleSitelinkLevel level,
      String campaignId,
      String adGroupId) {
    return switch (level) {
      case CAMPAIGN -> {
        if (campaignId == null) {
          throw new McpToolException("campaign_id is required when level is campaign.");
        }
        yield requireCampaign(connection, token, customerId, loginCustomerId, campaignId).id();
      }
      case AD_GROUP -> {
        if (adGroupId == null) {
          throw new McpToolException("ad_group_id is required when level is ad_group.");
        }
        yield requireAdGroup(connection, token, customerId, loginCustomerId, adGroupId).id();
      }
      case ACCOUNT -> null;
    };
  }

  public String requireHttpUrl(String url, String field) {
    if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
      throw new McpToolException(field + " must be an http(s) URL.");
    }
    return url;
  }

  // Turns the location entries a tool was given into geo target constants. An entry of
  // digits is treated as a geo target id and verified against Google (an id nobody
  // recognizes is refused, never sent into a mutate); anything else is a place name and
  // goes through GeoTargetConstantService, whose top suggestion wins — the canonical
  // name travels back in the result so the caller sees which "Kyiv" that was. Order is
  // preserved and duplicate geo targets collapse.
  public List<GoogleResolvedLocation> resolveLocations(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      List<String> entries,
      String field) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    if (entries.size() > MAX_LOCATIONS) {
      throw new McpToolException(field + " must contain at most " + MAX_LOCATIONS + " entries.");
    }
    List<String> ids = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (String entry : entries) {
      String trimmed = entry == null ? "" : entry.trim();
      if (trimmed.isBlank()) {
        throw new McpToolException("Every entry of " + field + " needs a location id or name.");
      }
      if (GEO_TARGET_ID.matcher(trimmed).matches()) {
        ids.add(trimmed);
      } else {
        names.add(trimmed);
      }
    }
    Map<String, String> idNames =
        ids.isEmpty()
            ? Map.of()
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .resolveGeoTargetNames(token, customerId, loginCustomerId, ids));
    List<GoogleGeoTargetSuggestionDto> suggestions =
        names.isEmpty()
            ? List.of()
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .suggestGeoTargetConstants(token, loginCustomerId, names, null, "en"));

    List<GoogleResolvedLocation> resolved = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String entry : entries) {
      String trimmed = entry.trim();
      GoogleResolvedLocation location =
          GEO_TARGET_ID.matcher(trimmed).matches()
              ? resolvedId(trimmed, idNames)
              : resolvedName(trimmed, suggestions);
      if (seen.add(location.geoTargetId())) {
        resolved.add(location);
      }
    }
    log.debug("Resolved {} of {} location entries in {}", resolved.size(), entries.size(), field);
    return resolved;
  }

  // Turns the language entries a tool was given into language constants. An entry may be
  // a language constant id ("1000"), an ISO code ("en", "uk") or a name ("English") —
  // callers say all three, and Google's table is small enough to fetch whole and match
  // in memory rather than guess. Order is preserved and duplicates collapse.
  public List<GoogleResolvedLanguage> resolveLanguages(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      List<String> entries,
      String field) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    if (entries.size() > MAX_LOCATIONS) {
      throw new McpToolException(field + " must contain at most " + MAX_LOCATIONS + " entries.");
    }
    List<GoogleLanguageConstantDto> languages =
        adsService.call(
            connection,
            () -> adsService.client().listLanguageConstants(token, customerId, loginCustomerId));

    List<GoogleResolvedLanguage> resolved = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String entry : entries) {
      String trimmed = entry == null ? "" : entry.trim();
      if (trimmed.isBlank()) {
        throw new McpToolException(
            "Every entry of " + field + " needs a language id, code or name.");
      }
      GoogleLanguageConstantDto language = matchLanguage(trimmed, languages);
      if (seen.add(language.id())) {
        resolved.add(
            new GoogleResolvedLanguage(trimmed, language.id(), language.code(), language.name()));
      }
    }
    log.debug("Resolved {} of {} language entries in {}", resolved.size(), entries.size(), field);
    return resolved;
  }

  private GoogleLanguageConstantDto matchLanguage(
      String entry, List<GoogleLanguageConstantDto> languages) {
    return languages.stream()
        .filter(
            language ->
                entry.equals(language.id())
                    || entry.equalsIgnoreCase(language.code())
                    || entry.equalsIgnoreCase(language.name()))
        .findFirst()
        .orElseThrow(
            () ->
                new McpToolException(
                    "\""
                        + entry
                        + "\" is not a language Google Ads can target. Use a language constant id"
                        + " (1000), an ISO code (en, uk, de) or its English name (English,"
                        + " Ukrainian)."));
  }

  private GoogleResolvedLocation resolvedId(String id, Map<String, String> idNames) {
    String name = idNames.get(id);
    if (name == null) {
      throw new McpToolException(
          "Geo target id "
              + id
              + " is not a location Google knows. Use google_find_locations to look the place up by"
              + " name and pass the id it returns.");
    }
    return new GoogleResolvedLocation(id, id, name);
  }

  private GoogleResolvedLocation resolvedName(
      String name, List<GoogleGeoTargetSuggestionDto> suggestions) {
    Optional<GoogleGeoTargetSuggestionDto> match =
        suggestions.stream()
            .filter(suggestion -> suggestion.id() != null)
            .filter(
                suggestion ->
                    suggestion.searchTerm() != null
                        && suggestion.searchTerm().equalsIgnoreCase(name))
            .findFirst();
    GoogleGeoTargetSuggestionDto suggestion =
        match.orElseThrow(
            () ->
                new McpToolException(
                    "Could not find a Google location matching \""
                        + name
                        + "\". Use google_find_locations to search for it, optionally with a"
                        + " country_code, and pass the id it returns."));
    return new GoogleResolvedLocation(
        name,
        suggestion.id(),
        suggestion.canonicalName() == null ? suggestion.name() : suggestion.canonicalName());
  }

  // Empty when the bid will actually be used; otherwise the sentence tools append to
  // their result text so nobody believes a stored bid is a live bid.
  public String biddingStrategyWarning(GoogleCampaignDto campaign) {
    if (MANUAL_CPC.equals(campaign.biddingStrategyType())) {
      return "";
    }
    return " Note: campaign \""
        + campaign.name()
        + "\" uses "
        + (campaign.biddingStrategyType() == null
            ? "an automated bidding strategy"
            : campaign.biddingStrategyType())
        + ", so Google ignores manual CPC bids — the bid is stored but does not affect delivery"
        + " until the campaign is switched to Manual CPC with google_update_campaign_bidding.";
  }
}
