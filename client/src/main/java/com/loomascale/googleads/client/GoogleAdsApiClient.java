package com.loomascale.googleads.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAccountBudgetDto;
import com.loomascale.googleads.client.dto.GoogleAdAccountDto;
import com.loomascale.googleads.client.dto.GoogleAdDto;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleAdTextAssetDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupAssetDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDetailDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupQuery;
import com.loomascale.googleads.client.dto.GoogleAssetGroupSignalDto;
import com.loomascale.googleads.client.dto.GoogleAssetLinkDto;
import com.loomascale.googleads.client.dto.GoogleAssetLinkSpec;
import com.loomascale.googleads.client.dto.GoogleAudienceInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleAudienceRowDto;
import com.loomascale.googleads.client.dto.GoogleBillingSetupDto;
import com.loomascale.googleads.client.dto.GoogleBrandDto;
import com.loomascale.googleads.client.dto.GoogleBrandExclusionDto;
import com.loomascale.googleads.client.dto.GoogleBrandSuggestionDto;
import com.loomascale.googleads.client.dto.GoogleCampaignAssetDto;
import com.loomascale.googleads.client.dto.GoogleCampaignBiddingSpec;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleCampaignGoalsDto;
import com.loomascale.googleads.client.dto.GoogleCampaignNegativeKeywordDto;
import com.loomascale.googleads.client.dto.GoogleCampaignNegativesDto;
import com.loomascale.googleads.client.dto.GoogleCampaignSettingsUpdate;
import com.loomascale.googleads.client.dto.GoogleConversionActionDto;
import com.loomascale.googleads.client.dto.GoogleConversionActionQuery;
import com.loomascale.googleads.client.dto.GoogleConversionActionStatsDto;
import com.loomascale.googleads.client.dto.GoogleConversionActionUpdateSpec;
import com.loomascale.googleads.client.dto.GoogleConversionGoalDto;
import com.loomascale.googleads.client.dto.GoogleCreatePmaxCampaignSpec;
import com.loomascale.googleads.client.dto.GoogleCreatePmaxResult;
import com.loomascale.googleads.client.dto.GoogleCustomerDto;
import com.loomascale.googleads.client.dto.GoogleGeoInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleGeoRowDto;
import com.loomascale.googleads.client.dto.GoogleGeoTargetSuggestionDto;
import com.loomascale.googleads.client.dto.GoogleInsightsQuery;
import com.loomascale.googleads.client.dto.GoogleInsightsRowDto;
import com.loomascale.googleads.client.dto.GoogleKeywordCreateSpec;
import com.loomascale.googleads.client.dto.GoogleKeywordDto;
import com.loomascale.googleads.client.dto.GoogleKeywordIdeaDto;
import com.loomascale.googleads.client.dto.GoogleKeywordIdeaSpec;
import com.loomascale.googleads.client.dto.GoogleLanguageConstantDto;
import com.loomascale.googleads.client.dto.GoogleLanguageCriterionDto;
import com.loomascale.googleads.client.dto.GoogleLocationCriterionDto;
import com.loomascale.googleads.client.dto.GoogleNegativeKeywordSpec;
import com.loomascale.googleads.client.dto.GoogleSearchTermDto;
import com.loomascale.googleads.client.dto.GoogleSearchTermQuery;
import com.loomascale.googleads.client.dto.GoogleSitelinkDto;
import com.loomascale.googleads.client.dto.GoogleSitelinkLevel;
import com.loomascale.googleads.client.dto.GoogleSitelinkMetricsDto;
import com.loomascale.googleads.client.dto.GoogleSitelinkQuery;
import com.loomascale.googleads.client.dto.GoogleSitelinkSpec;
import com.loomascale.googleads.client.dto.GoogleSitelinkUpdateSpec;
import com.loomascale.googleads.client.dto.GoogleTargetingCriteriaDto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.extern.slf4j.Slf4j;

// Raw Google Ads REST API (GAQL over googleAds:searchStream plus the per-resource
// :mutate services). Deliberately hand-rolled like MetaAdsClient — the official
// google-ads client drags in gRPC/protobuf for no benefit here. Money arrives in
// micros and is normalized to minor units (cents) here, so nothing above this
// layer juggles micros; the tools convert minor units to a displayable amount.
@Slf4j
public class GoogleAdsApiClient {

  // Insights levels, each backed by a different GAQL resource.
  public static final String LEVEL_ACCOUNT = "account";
  public static final String LEVEL_CAMPAIGN = "campaign";
  public static final String LEVEL_AD_GROUP = "ad_group";
  public static final String LEVEL_KEYWORD = "keyword";

  // Bidding strategy enum values the campaign bidding update understands. TARGET_SPEND
  // is what Google Ads shows as "Maximize clicks"; its cpc_bid_ceiling is the Max CPC
  // limit. MANUAL_CPC is the only strategy under which manual bids affect delivery.
  public static final String STRATEGY_TARGET_SPEND = "TARGET_SPEND";

  // The time components Google's reference prescribes for a campaign scheduled by whole
  // days, appended to the date a caller passes.
  private static final String START_OF_DAY = " 00:00:00";
  private static final String END_OF_DAY = " 23:59:59";

  // The sentinel the old date-only end_date field used for "no end date". See
  // endDateTimeOrNull for why it is still matched now that the field is a date-time.
  static final String NO_END_DATE = "2037-12-30";
  public static final String STRATEGY_MANUAL_CPC = "MANUAL_CPC";
  // Maximize conversions. Google refuses the switch unless the campaign has at least one
  // biddable conversion goal, which is why the tool checks the goals before mutating.
  public static final String STRATEGY_MAXIMIZE_CONVERSIONS = "MAXIMIZE_CONVERSIONS";

  // Maximize conversion value — bidding on revenue rather than on conversion count, and
  // what a Performance Max campaign with a target ROAS uses. Note that TARGET_ROAS is a
  // PORTFOLIO strategy type in the Google Ads API and cannot be set on a campaign: the
  // campaign-level way to bid to a return on ad spend is this strategy with its own
  // target_roas leaf. Google refuses the switch without a biddable conversion goal, same as
  // MAXIMIZE_CONVERSIONS.
  public static final String STRATEGY_MAXIMIZE_CONVERSION_VALUE = "MAXIMIZE_CONVERSION_VALUE";

  // campaign_criterion.type of the two axes read together by listTargetingCriteria.
  private static final String LANGUAGE_CRITERION = "LANGUAGE";

  // Row caps for the reports that are per-keyword or per-search-term. An account
  // with a few hundred keywords produces more rows than a tool result should carry,
  // and the cap lands in GAQL as a literal, so it is always a number we produced.
  static final int DEFAULT_REPORT_ROWS = 100;
  static final int MAX_REPORT_ROWS = 1000;

  // Account state and billing queries. Fixed shape, so they live as constants beside the
  // metric field lists rather than as builders.
  static final String CUSTOMER_GAQL =
      "SELECT customer.id, customer.descriptive_name, customer.status, customer.currency_code,"
          + " customer.time_zone, customer.test_account, customer.manager FROM customer LIMIT 1";

  static final String BILLING_SETUP_GAQL =
      "SELECT billing_setup.id, billing_setup.status,"
          + " billing_setup.payments_account_info.payments_account_id,"
          + " billing_setup.payments_account_info.payments_account_name,"
          + " billing_setup.payments_account_info.payments_profile_id,"
          + " billing_setup.payments_account_info.payments_profile_name,"
          + " billing_setup.start_date_time, billing_setup.end_date_time FROM billing_setup";

  static final String ACCOUNT_BUDGET_GAQL =
      "SELECT account_budget.id, account_budget.name, account_budget.status,"
          + " account_budget.approved_spending_limit_micros,"
          + " account_budget.approved_spending_limit_type,"
          + " account_budget.adjusted_spending_limit_micros,"
          + " account_budget.proposed_spending_limit_micros,"
          + " account_budget.proposed_spending_limit_type,"
          + " account_budget.total_adjustments_micros,"
          + " account_budget.amount_served_micros, account_budget.approved_end_date_time"
          + " FROM account_budget";

  private static final String METRIC_FIELDS =
      "metrics.impressions, metrics.clicks, metrics.cost_micros, metrics.ctr,"
          + " metrics.average_cpc, metrics.conversions, metrics.conversions_value";

  // Search impression share and where the missed share went. Only asked for on
  // request: they are unavailable on some resources, and a query that selects an
  // unsupported metric fails outright rather than omitting it. Budget lost share is
  // attributed to the campaign's budget, so Google exposes it only on the customer
  // and campaign resources — selecting it FROM ad_group or keyword_view is an
  // INVALID_ARGUMENT error.
  private static final String IMPRESSION_SHARE_FIELDS =
      "metrics.search_impression_share, metrics.search_budget_lost_impression_share,"
          + " metrics.search_rank_lost_impression_share";

  private static final String IMPRESSION_SHARE_FIELDS_NO_BUDGET =
      "metrics.search_impression_share, metrics.search_rank_lost_impression_share";

  // Reporting dimensions callers may split rows by, in the order tools should offer
  // them. An allowlist rather than a sanitizer, because these interpolate straight
  // into the SELECT clause and only a handful are worth the row multiplication.
  public static final List<String> SEGMENTS =
      List.of("device", "day_of_week", "hour", "ad_network_type");

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper;
  private final GoogleAdsApiConfig config;

  public GoogleAdsApiClient(ObjectMapper objectMapper, GoogleAdsApiConfig config) {
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public String apiBase() {
    return config.baseUrl();
  }

  // Customers the OAuth user can log into directly (their own accounts and MCCs).
  public List<String> listAccessibleCustomers(String accessToken) {
    JsonNode node =
        get(
            apiBase() + "/customers:listAccessibleCustomers",
            accessToken,
            null,
            "Google Ads listAccessibleCustomers",
            GoogleAdsRequestContext.ofAccount(null, null));
    List<String> ids = new ArrayList<>();
    for (JsonNode name : node.path("resourceNames")) {
      // resource names arrive as customers/1234567890
      ids.add(name.asText().substring(name.asText().lastIndexOf('/') + 1));
    }
    log.debug("Accessible Google Ads customers: {}", ids);
    return ids;
  }

  // The account tree visible under one accessible customer, that customer itself
  // included (customer_client level 0). loginCustomerId of every returned account
  // is the accessible customer it was found under.
  public List<GoogleAdAccountDto> listCustomerClients(
      String accessToken, String accessibleCustomerId) {
    String gaql =
        "SELECT customer_client.id, customer_client.descriptive_name,"
            + " customer_client.currency_code, customer_client.time_zone,"
            + " customer_client.manager, customer_client.status"
            + " FROM customer_client WHERE customer_client.status = 'ENABLED'";
    List<GoogleAdAccountDto> accounts = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, accessibleCustomerId, accessibleCustomerId, gaql)) {
      JsonNode client = row.path("customerClient");
      accounts.add(
          new GoogleAdAccountDto(
              client.path("id").asText(),
              client.path("descriptiveName").asText(null),
              client.path("currencyCode").asText(null),
              client.path("timeZone").asText(null),
              client.path("manager").asBoolean(false),
              accessibleCustomerId));
    }
    log.debug("Customer {} exposes {} client accounts", accessibleCustomerId, accounts.size());
    return accounts;
  }

  // The ISO currency every money field of this account is denominated in. Cheap
  // single-row query; callers cache the answer on the connection.
  public String customerCurrency(String accessToken, String customerId, String loginCustomerId) {
    String gaql = "SELECT customer.currency_code FROM customer LIMIT 1";
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, gaql)) {
      String currency = row.path("customer").path("currencyCode").asText(null);
      if (currency != null && !currency.isBlank()) {
        log.debug("Google Ads customer {} bills in {}", customerId, currency);
        return currency;
      }
    }
    return null;
  }

  // The account row itself. customer.status is the only account-level stop the API
  // states outright: SUSPENDED or CANCELED and nothing in it can serve.
  public GoogleCustomerDto customerDetails(
      String accessToken, String customerId, String loginCustomerId) {
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, CUSTOMER_GAQL)) {
      JsonNode customer = row.path("customer");
      GoogleCustomerDto details =
          new GoogleCustomerDto(
              customer.path("id").asText(customerId),
              customer.path("descriptiveName").asText(null),
              customer.path("status").asText(null),
              customer.path("currencyCode").asText(null),
              customer.path("timeZone").asText(null),
              customer.path("testAccount").asBoolean(false),
              customer.path("manager").asBoolean(false));
      log.debug("Customer {} is {} (test={})", customerId, details.status(), details.testAccount());
      return details;
    }
    return null;
  }

  // Billing setups link the account to a Google payments account. Refused with
  // PERMISSION_DENIED for users without billing access, which callers handle rather
  // than treating as an outage.
  public List<GoogleBillingSetupDto> listBillingSetups(
      String accessToken, String customerId, String loginCustomerId) {
    List<GoogleBillingSetupDto> setups = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, BILLING_SETUP_GAQL)) {
      JsonNode setup = row.path("billingSetup");
      JsonNode payments = setup.path("paymentsAccountInfo");
      setups.add(
          new GoogleBillingSetupDto(
              setup.path("id").asText(null),
              setup.path("status").asText(null),
              payments.path("paymentsAccountId").asText(null),
              payments.path("paymentsAccountName").asText(null),
              payments.path("paymentsProfileId").asText(null),
              payments.path("paymentsProfileName").asText(null),
              setup.path("startDateTime").asText(null),
              setup.path("endDateTime").asText(null)));
    }
    log.debug("Customer {} has {} billing setups", customerId, setups.size());
    return setups;
  }

  // Account budgets cap spend above every campaign budget on invoiced accounts.
  public List<GoogleAccountBudgetDto> listAccountBudgets(
      String accessToken, String customerId, String loginCustomerId) {
    List<GoogleAccountBudgetDto> budgets = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, ACCOUNT_BUDGET_GAQL)) {
      JsonNode budget = row.path("accountBudget");
      budgets.add(
          new GoogleAccountBudgetDto(
              budget.path("id").asText(null),
              budget.path("name").asText(null),
              budget.path("status").asText(null),
              budget.hasNonNull("approvedSpendingLimitMicros")
                  ? microsToCents(budget.path("approvedSpendingLimitMicros").asLong())
                  : null,
              budget.path("approvedSpendingLimitType").asText(null),
              budget.hasNonNull("adjustedSpendingLimitMicros")
                  ? microsToCents(budget.path("adjustedSpendingLimitMicros").asLong())
                  : null,
              budget.hasNonNull("proposedSpendingLimitMicros")
                  ? microsToCents(budget.path("proposedSpendingLimitMicros").asLong())
                  : null,
              budget.path("proposedSpendingLimitType").asText(null),
              budget.hasNonNull("totalAdjustmentsMicros")
                  ? microsToCents(budget.path("totalAdjustmentsMicros").asLong())
                  : null,
              budget.hasNonNull("amountServedMicros")
                  ? microsToCents(budget.path("amountServedMicros").asLong())
                  : null,
              budget.path("approvedEndDateTime").asText(null)));
    }
    log.debug("Customer {} has {} account budgets", customerId, budgets.size());
    return budgets;
  }

  // A package-private builder rather than an inline string, like every other query in this
  // class. It was inline once, which is why nothing could pin it in a test — and a field
  // name added to it from memory instead of from the v24 reference took down every Google
  // campaign tool at runtime. The names here are the reporting field names, not the proto
  // ones: campaign.start_date and campaign.end_date do NOT exist in v24.
  static String campaignsGaql() {
    return "SELECT campaign.id, campaign.name, campaign.status,"
        + " campaign.advertising_channel_type, campaign.bidding_strategy_type,"
        + " campaign.bidding_strategy, campaign.target_spend.cpc_bid_ceiling_micros,"
        + " campaign.maximize_conversions.target_cpa_micros,"
        + " campaign.maximize_conversion_value.target_roas,"
        + " campaign.start_date_time, campaign.end_date_time,"
        + " campaign.brand_guidelines_enabled,"
        + " campaign.primary_status, campaign.primary_status_reasons,"
        + " campaign.geo_target_type_setting.positive_geo_target_type,"
        + " campaign.geo_target_type_setting.negative_geo_target_type,"
        + " campaign_budget.resource_name,"
        + " campaign_budget.amount_micros, campaign_budget.explicitly_shared"
        + " FROM campaign WHERE campaign.status != 'REMOVED' ORDER BY campaign.id";
  }

  public List<GoogleCampaignDto> listCampaigns(
      String accessToken, String customerId, String loginCustomerId) {
    List<GoogleCampaignDto> campaigns = new ArrayList<>();
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, campaignsGaql())) {
      JsonNode campaign = row.path("campaign");
      JsonNode budget = row.path("campaignBudget");
      List<String> primaryStatusReasons = new ArrayList<>();
      for (JsonNode reason : campaign.path("primaryStatusReasons")) {
        primaryStatusReasons.add(reason.asText());
      }
      campaigns.add(
          new GoogleCampaignDto(
              campaign.path("id").asText(),
              campaign.path("name").asText(null),
              campaign.path("status").asText(null),
              campaign.path("advertisingChannelType").asText(null),
              budget.hasNonNull("amountMicros")
                  ? microsToCents(budget.path("amountMicros").asLong())
                  : null,
              budget.path("resourceName").asText(null),
              budget.path("explicitlyShared").asBoolean(false),
              campaign.path("biddingStrategyType").asText(null),
              campaign.path("targetSpend").hasNonNull("cpcBidCeilingMicros")
                  ? microsToCents(campaign.path("targetSpend").path("cpcBidCeilingMicros").asLong())
                  : null,
              campaign.path("biddingStrategy").asText(null),
              campaign.path("geoTargetTypeSetting").path("positiveGeoTargetType").asText(null),
              campaign.path("geoTargetTypeSetting").path("negativeGeoTargetType").asText(null),
              campaign.path("primaryStatus").asText(null),
              primaryStatusReasons,
              campaign.path("maximizeConversions").hasNonNull("targetCpaMicros")
                  ? microsToCents(
                      campaign.path("maximizeConversions").path("targetCpaMicros").asLong())
                  : null,
              campaign.path("maximizeConversionValue").hasNonNull("targetRoas")
                  ? campaign.path("maximizeConversionValue").path("targetRoas").asDouble()
                  : null,
              campaign.path("startDateTime").asText(null),
              endDateTimeOrNull(campaign.path("endDateTime").asText(null)),
              campaign.hasNonNull("brandGuidelinesEnabled")
                  ? campaign.get("brandGuidelinesEnabled").asBoolean()
                  : null));
    }
    return campaigns;
  }

  // "Runs until it is paused", normalized to no value.
  //
  // Two shapes are accepted because it is not certain which one v24 returns. The reference
  // says an indefinite campaign is one whose end_date_time has been CLEARED, which means an
  // absent or blank field; but the old date-only field reported the sentinel 2037-12-30
  // instead, and reporting that raw would tell a user their campaign ends in 2037. Handling
  // both is correct either way, and cheaper than being wrong.
  //
  // UNVERIFIED: which branch actually fires needs one live read of a campaign with no end
  // date. Until then neither is removed.
  static String endDateTimeOrNull(String endDateTime) {
    if (endDateTime == null || endDateTime.isBlank()) {
      return null;
    }
    return endDateTime.startsWith(NO_END_DATE) ? null : endDateTime;
  }

  public List<GoogleAdGroupDto> listAdGroups(
      String accessToken, String customerId, String loginCustomerId, String campaignId) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT ad_group.id, ad_group.name, ad_group.status, ad_group.type,"
                + " ad_group.cpc_bid_micros, campaign.id"
                + " FROM ad_group WHERE ad_group.status != 'REMOVED'");
    if (campaignId != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(campaignId));
    }
    List<GoogleAdGroupDto> adGroups = new ArrayList<>();
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, gaql.toString())) {
      JsonNode adGroup = row.path("adGroup");
      adGroups.add(
          new GoogleAdGroupDto(
              adGroup.path("id").asText(),
              adGroup.path("name").asText(null),
              adGroup.path("status").asText(null),
              adGroup.path("type").asText(null),
              row.path("campaign").path("id").asText(),
              adGroup.hasNonNull("cpcBidMicros")
                  ? microsToCents(adGroup.path("cpcBidMicros").asLong())
                  : null));
    }
    return adGroups;
  }

  public List<GoogleAdDto> listAds(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      String adGroupId,
      String adId) {
    List<GoogleAdDto> ads = new ArrayList<>();
    for (JsonNode row :
        searchStream(
            accessToken, customerId, loginCustomerId, adsGaql(campaignId, adGroupId, adId))) {
      JsonNode ad = row.path("adGroupAd").path("ad");
      JsonNode responsiveSearchAd = ad.path("responsiveSearchAd");
      List<String> finalUrls = new ArrayList<>();
      for (JsonNode url : ad.path("finalUrls")) {
        finalUrls.add(url.asText());
      }
      ads.add(
          new GoogleAdDto(
              ad.path("id").asText(),
              ad.path("name").asText(null),
              ad.path("type").asText(null),
              row.path("adGroupAd").path("status").asText(null),
              row.path("adGroupAd").path("policySummary").path("approvalStatus").asText(null),
              row.path("adGroup").path("id").asText(),
              row.path("campaign").path("id").asText(),
              finalUrls,
              textAssets(responsiveSearchAd.path("headlines")),
              textAssets(responsiveSearchAd.path("descriptions")),
              responsiveSearchAd.path("path1").asText(null),
              responsiveSearchAd.path("path2").asText(null)));
    }
    log.debug("Customer {} returned {} ads", customerId, ads.size());
    return ads;
  }

  // Headlines and descriptions arrive as assets carrying an optional pin; an ad type
  // without them yields a missing node, which iterates as empty.
  private static List<GoogleAdTextAssetDto> textAssets(JsonNode assets) {
    List<GoogleAdTextAssetDto> texts = new ArrayList<>();
    for (JsonNode asset : assets) {
      texts.add(
          new GoogleAdTextAssetDto(
              asset.path("text").asText(null), asset.path("pinnedField").asText(null)));
    }
    return texts;
  }

  static String adsGaql(String campaignId, String adGroupId, String adId) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT ad_group_ad.ad.id, ad_group_ad.ad.name, ad_group_ad.ad.type,"
                + " ad_group_ad.status, ad_group_ad.policy_summary.approval_status,"
                + " ad_group_ad.ad.final_urls,"
                + " ad_group_ad.ad.responsive_search_ad.headlines,"
                + " ad_group_ad.ad.responsive_search_ad.descriptions,"
                + " ad_group_ad.ad.responsive_search_ad.path1,"
                + " ad_group_ad.ad.responsive_search_ad.path2,"
                + " ad_group.id, campaign.id"
                + " FROM ad_group_ad WHERE ad_group_ad.status != 'REMOVED'");
    if (campaignId != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(campaignId));
    }
    if (adGroupId != null) {
      gaql.append(" AND ad_group.id = ").append(sanitizeId(adGroupId));
    }
    if (adId != null) {
      gaql.append(" AND ad_group_ad.ad.id = ").append(sanitizeId(adId));
    }
    return gaql.toString();
  }

  // Metrics for a date range, aggregated by account, campaign, ad group, or keyword.
  // datePreset must be a GAQL DURING keyword (TODAY, LAST_7_DAYS, ...); otherwise
  // since/until dates are used. The two lowest levels are row-capped by the query.
  public List<GoogleInsightsRowDto> getInsights(
      String accessToken, String customerId, String loginCustomerId, GoogleInsightsQuery query) {
    String level = query.level();
    boolean keywordLevel = LEVEL_KEYWORD.equals(level);
    List<GoogleInsightsRowDto> rows = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, insightsGaql(query))) {
      JsonNode metrics = row.path("metrics");
      JsonNode campaign = row.path("campaign");
      JsonNode adGroup = row.path("adGroup");
      JsonNode criterion = row.path("adGroupCriterion");
      Map<String, String> segments = new LinkedHashMap<>();
      for (String segment : segmentsOf(query)) {
        segments.put(segment, row.path("segments").path(segmentJsonKey(segment)).asText(null));
      }
      rows.add(
          new GoogleInsightsRowDto(
              insightsEntityId(level, campaign, adGroup, criterion),
              insightsEntityName(level, campaign, adGroup, criterion),
              campaign.path("id").asText(null),
              campaign.path("name").asText(null),
              adGroup.path("id").asText(null),
              adGroup.path("name").asText(null),
              keywordLevel ? criterion.path("keyword").path("matchType").asText(null) : null,
              metrics.path("impressions").asLong(0),
              metrics.path("clicks").asLong(0),
              microsToCents(metrics.path("costMicros").asLong(0)),
              metrics.path("ctr").asDouble(0),
              metrics.path("averageCpc").asLong(0) / 10_000.0,
              metrics.path("conversions").asDouble(0),
              // conversions_value is reported in currency units, not micros.
              Math.round(metrics.path("conversionsValue").asDouble(0) * 100),
              segments,
              // Absent unless the query asked for them, and absent is not zero: a
              // campaign with no search impressions has no share to report.
              nullableDouble(metrics, "searchImpressionShare"),
              nullableDouble(metrics, "searchBudgetLostImpressionShare"),
              nullableDouble(metrics, "searchRankLostImpressionShare")));
    }
    log.debug(
        "Google Ads {} insights returned {} rows for customer {}", level, rows.size(), customerId);
    return rows;
  }

  // Positive and negative keyword criteria, optionally narrowed to one campaign or
  // ad group. Negatives come back in the same list flagged by `negative` — the
  // caller decides, because "which negative is blocking me" is as much a delivery
  // question as "which positives exist". systemServingStatus and primaryStatus are
  // the fields that explain a keyword that never serves.
  public List<GoogleKeywordDto> listKeywords(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      String adGroupId) {
    List<GoogleKeywordDto> keywords = new ArrayList<>();
    for (JsonNode row :
        searchStream(
            accessToken, customerId, loginCustomerId, keywordsGaql(campaignId, adGroupId))) {
      JsonNode criterion = row.path("adGroupCriterion");
      JsonNode quality = criterion.path("qualityInfo");
      List<String> reasons = new ArrayList<>();
      for (JsonNode reason : criterion.path("primaryStatusReasons")) {
        reasons.add(reason.asText());
      }
      keywords.add(
          new GoogleKeywordDto(
              criterion.path("criterionId").asText(null),
              criterion.path("keyword").path("text").asText(null),
              criterion.path("keyword").path("matchType").asText(null),
              criterion.path("status").asText(null),
              criterion.path("negative").asBoolean(false),
              criterion.path("systemServingStatus").asText(null),
              criterion.path("primaryStatus").asText(null),
              reasons,
              quality.hasNonNull("qualityScore") ? quality.path("qualityScore").asInt() : null,
              criterion.hasNonNull("effectiveCpcBidMicros")
                  ? microsToCents(criterion.path("effectiveCpcBidMicros").asLong())
                  : null,
              row.path("adGroup").path("id").asText(null),
              row.path("adGroup").path("name").asText(null),
              row.path("campaign").path("id").asText(null)));
    }
    log.debug("Customer {} matched {} keyword criteria", customerId, keywords.size());
    return keywords;
  }

  // What users actually typed, and whether a keyword already covers it. Ordered by
  // impressions so the capped result keeps the terms that matter.
  public List<GoogleSearchTermDto> listSearchTerms(
      String accessToken, String customerId, String loginCustomerId, GoogleSearchTermQuery query) {
    List<GoogleSearchTermDto> terms = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, searchTermsGaql(query))) {
      JsonNode view = row.path("searchTermView");
      JsonNode keyword = row.path("segments").path("keyword").path("info");
      JsonNode metrics = row.path("metrics");
      terms.add(
          new GoogleSearchTermDto(
              view.path("searchTerm").asText(null),
              view.path("status").asText(null),
              keyword.path("text").asText(null),
              keyword.path("matchType").asText(null),
              row.path("adGroup").path("id").asText(null),
              row.path("campaign").path("id").asText(null),
              metrics.path("impressions").asLong(0),
              metrics.path("clicks").asLong(0),
              microsToCents(metrics.path("costMicros").asLong(0)),
              metrics.path("ctr").asDouble(0),
              metrics.path("conversions").asDouble(0)));
    }
    log.debug("Customer {} returned {} search terms", customerId, terms.size());
    return terms;
  }

  // Performance by location. geographic_view reports location ids, not names, so
  // callers wanting readable rows follow this with resolveGeoTargetNames.
  public List<GoogleGeoRowDto> getGeoInsights(
      String accessToken, String customerId, String loginCustomerId, GoogleGeoInsightsQuery query) {
    List<GoogleGeoRowDto> rows = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, geoInsightsGaql(query))) {
      JsonNode view = row.path("geographicView");
      JsonNode metrics = row.path("metrics");
      rows.add(
          new GoogleGeoRowDto(
              view.path("countryCriterionId").asText(null),
              null,
              null,
              view.path("locationType").asText(null),
              row.path("campaign").path("id").asText(null),
              row.path("campaign").path("name").asText(null),
              metrics.path("impressions").asLong(0),
              metrics.path("clicks").asLong(0),
              microsToCents(metrics.path("costMicros").asLong(0)),
              metrics.path("ctr").asDouble(0),
              metrics.path("conversions").asDouble(0),
              Math.round(metrics.path("conversionsValue").asDouble(0) * 100)));
    }
    log.debug("Customer {} returned {} geo rows", customerId, rows.size());
    return rows;
  }

  // Names for the geo target ids a geo report returned, keyed by id. The ids come
  // from our own previous response and still go through sanitizeId, because they
  // are interpolated into the IN clause.
  public Map<String, String> resolveGeoTargetNames(
      String accessToken, String customerId, String loginCustomerId, List<String> locationIds) {
    Map<String, String> names = new LinkedHashMap<>();
    if (locationIds.isEmpty()) {
      return names;
    }
    for (JsonNode row :
        searchStream(
            accessToken, customerId, loginCustomerId, geoTargetConstantsGaql(locationIds))) {
      JsonNode constant = row.path("geoTargetConstant");
      String id = constant.path("id").asText(null);
      if (id != null) {
        // canonical_name is "Kyiv,Kyiv city,Ukraine" — unambiguous where name alone
        // ("Kyiv") is not, so it is what gets stored.
        names.put(id, constant.path("canonicalName").asText(constant.path("name").asText(null)));
      }
    }
    log.debug("Resolved {} of {} geo target names", names.size(), locationIds.size());
    return names;
  }

  // Both targeting axes of a campaign in one query: the locations it targets or excludes
  // and the languages it selected. Geo target ids arrive as resource names
  // ("geoTargetConstants/1012852") and language ids as "languageConstants/1000"; the
  // readable names come from one follow-up lookup each, skipped when that axis is empty.
  public GoogleTargetingCriteriaDto listTargetingCriteria(
      String accessToken, String customerId, String loginCustomerId, String campaignId) {
    List<GoogleLocationCriterionDto> locations = new ArrayList<>();
    List<GoogleLanguageCriterionDto> languages = new ArrayList<>();
    List<String> geoTargetIds = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, targetingCriteriaGaql(campaignId))) {
      JsonNode criterion = row.path("campaignCriterion");
      String campaign = row.path("campaign").path("id").asText(null);
      if (LANGUAGE_CRITERION.equals(criterion.path("type").asText(null))) {
        languages.add(
            new GoogleLanguageCriterionDto(
                campaign,
                criterion.path("criterionId").asText(null),
                lastSegment(criterion.path("language").path("languageConstant").asText(null)),
                null,
                null,
                criterion.path("status").asText(null)));
        continue;
      }
      String geoTargetId =
          lastSegment(criterion.path("location").path("geoTargetConstant").asText(null));
      if (geoTargetId != null) {
        geoTargetIds.add(geoTargetId);
      }
      locations.add(
          new GoogleLocationCriterionDto(
              campaign,
              criterion.path("criterionId").asText(null),
              geoTargetId,
              null,
              criterion.path("negative").asBoolean(false),
              criterion.hasNonNull("bidModifier") ? criterion.path("bidModifier").asDouble() : null,
              criterion.path("status").asText(null)));
    }
    log.debug(
        "Customer {} campaign {} has {} location and {} language criteria",
        customerId,
        campaignId,
        locations.size(),
        languages.size());
    return new GoogleTargetingCriteriaDto(
        namedLocations(accessToken, customerId, loginCustomerId, locations, geoTargetIds),
        namedLanguages(accessToken, customerId, loginCustomerId, languages));
  }

  private List<GoogleLocationCriterionDto> namedLocations(
      String accessToken,
      String customerId,
      String loginCustomerId,
      List<GoogleLocationCriterionDto> locations,
      List<String> geoTargetIds) {
    if (locations.isEmpty()) {
      return locations;
    }
    Map<String, String> names =
        resolveGeoTargetNames(accessToken, customerId, loginCustomerId, geoTargetIds);
    List<GoogleLocationCriterionDto> named = new ArrayList<>();
    for (GoogleLocationCriterionDto criterion : locations) {
      named.add(
          new GoogleLocationCriterionDto(
              criterion.campaignId(),
              criterion.criterionId(),
              criterion.geoTargetId(),
              names.get(criterion.geoTargetId()),
              criterion.negative(),
              criterion.bidModifier(),
              criterion.status()));
    }
    return named;
  }

  private List<GoogleLanguageCriterionDto> namedLanguages(
      String accessToken,
      String customerId,
      String loginCustomerId,
      List<GoogleLanguageCriterionDto> languages) {
    if (languages.isEmpty()) {
      return languages;
    }
    Map<String, GoogleLanguageConstantDto> constants = new LinkedHashMap<>();
    for (GoogleLanguageConstantDto language :
        listLanguageConstants(accessToken, customerId, loginCustomerId)) {
      constants.put(language.id(), language);
    }
    List<GoogleLanguageCriterionDto> named = new ArrayList<>();
    for (GoogleLanguageCriterionDto criterion : languages) {
      GoogleLanguageConstantDto language = constants.get(criterion.languageId());
      named.add(
          new GoogleLanguageCriterionDto(
              criterion.campaignId(),
              criterion.criterionId(),
              criterion.languageId(),
              language == null ? null : language.code(),
              language == null ? null : language.name(),
              criterion.status()));
    }
    return named;
  }

  // Every language Google lets a campaign target — some fifty rows, so the whole table
  // travels at once and callers match against it in memory.
  public List<GoogleLanguageConstantDto> listLanguageConstants(
      String accessToken, String customerId, String loginCustomerId) {
    String gaql =
        "SELECT language_constant.id, language_constant.code, language_constant.name"
            + " FROM language_constant WHERE language_constant.targetable = TRUE";
    List<GoogleLanguageConstantDto> languages = new ArrayList<>();
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, gaql)) {
      JsonNode language = row.path("languageConstant");
      languages.add(
          new GoogleLanguageConstantDto(
              language.path("id").asText(null),
              language.path("code").asText(null),
              language.path("name").asText(null)));
    }
    log.debug("Google offers {} targetable languages", languages.size());
    return languages;
  }

  // Geo target constants matching place names, through GeoTargetConstantService — the
  // only way to turn "Kyiv" into an id. Not customer-scoped, so the path carries no
  // customer, but the developer token and bearer auth are the same.
  public List<GoogleGeoTargetSuggestionDto> suggestGeoTargetConstants(
      String accessToken,
      String loginCustomerId,
      List<String> names,
      String countryCode,
      String locale) {
    if (names == null || names.isEmpty()) {
      return List.of();
    }
    ObjectNode body = objectMapper.createObjectNode();
    ArrayNode locationNames = body.putObject("locationNames").putArray("names");
    names.forEach(locationNames::add);
    body.put("locale", locale == null || locale.isBlank() ? "en" : locale);
    if (countryCode != null && !countryCode.isBlank()) {
      body.put("countryCode", countryCode);
    }
    JsonNode response =
        post(
            apiBase() + "/geoTargetConstants:suggest",
            accessToken,
            loginCustomerId,
            body,
            "Google Ads suggestGeoTargetConstants",
            GoogleAdsRequestContext.of(null, loginCustomerId, body));
    List<GoogleGeoTargetSuggestionDto> suggestions = new ArrayList<>();
    for (JsonNode suggestion : response.path("geoTargetConstantSuggestions")) {
      JsonNode constant = suggestion.path("geoTargetConstant");
      suggestions.add(
          new GoogleGeoTargetSuggestionDto(
              suggestion.path("searchTerm").asText(null),
              constant.path("id").asText(null),
              constant.path("name").asText(null),
              constant.path("canonicalName").asText(null),
              constant.path("countryCode").asText(null),
              constant.path("targetType").asText(null),
              constant.path("status").asText(null)));
    }
    log.debug("Suggest returned {} geo targets for {}", suggestions.size(), names);
    return suggestions;
  }

  // Performance by audience criterion attached to an ad group.
  public List<GoogleAudienceRowDto> getAudienceInsights(
      String accessToken,
      String customerId,
      String loginCustomerId,
      GoogleAudienceInsightsQuery query) {
    List<GoogleAudienceRowDto> rows = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, audienceInsightsGaql(query))) {
      JsonNode criterion = row.path("adGroupCriterion");
      JsonNode metrics = row.path("metrics");
      rows.add(
          new GoogleAudienceRowDto(
              criterion.path("criterionId").asText(null),
              criterion.path("type").asText(null),
              audienceName(criterion),
              row.path("adGroup").path("id").asText(null),
              row.path("adGroup").path("name").asText(null),
              row.path("campaign").path("id").asText(null),
              row.path("campaign").path("name").asText(null),
              metrics.path("impressions").asLong(0),
              metrics.path("clicks").asLong(0),
              microsToCents(metrics.path("costMicros").asLong(0)),
              metrics.path("ctr").asDouble(0),
              metrics.path("conversions").asDouble(0),
              Math.round(metrics.path("conversionsValue").asDouble(0) * 100)));
    }
    log.debug("Customer {} returned {} audience rows", customerId, rows.size());
    return rows;
  }

  // Whichever of the audience criterion shapes this row actually carries.
  private static String audienceName(JsonNode criterion) {
    String userList = criterion.path("userList").path("userList").asText(null);
    if (userList != null) {
      return userList;
    }
    String interest = criterion.path("userInterest").path("userInterestCategory").asText(null);
    if (interest != null) {
      return interest;
    }
    return criterion.path("audience").path("audience").asText(null);
  }

  // Performance Max asset groups with their performance. PMax spends per asset
  // group, so this is the only level at which a PMax campaign can be judged —
  // campaign rows hide which creative set is carrying it.
  public List<GoogleAssetGroupDto> listAssetGroups(
      String accessToken, String customerId, String loginCustomerId, GoogleAssetGroupQuery query) {
    List<GoogleAssetGroupDto> groups = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, assetGroupsGaql(query))) {
      JsonNode group = row.path("assetGroup");
      JsonNode metrics = row.path("metrics");
      List<String> reasons = new ArrayList<>();
      for (JsonNode reason : group.path("primaryStatusReasons")) {
        reasons.add(reason.asText());
      }
      groups.add(
          new GoogleAssetGroupDto(
              group.path("id").asText(null),
              group.path("name").asText(null),
              group.path("status").asText(null),
              group.path("primaryStatus").asText(null),
              reasons,
              group.path("adStrength").asText(null),
              row.path("campaign").path("id").asText(null),
              row.path("campaign").path("name").asText(null),
              metrics.path("impressions").asLong(0),
              metrics.path("clicks").asLong(0),
              microsToCents(metrics.path("costMicros").asLong(0)),
              metrics.path("ctr").asDouble(0),
              metrics.path("conversions").asDouble(0),
              Math.round(metrics.path("conversionsValue").asDouble(0) * 100)));
    }
    log.debug("Customer {} returned {} asset groups", customerId, groups.size());
    return groups;
  }

  // One asset group as an editable object. Deliberately not listAssetGroups: that query
  // segments by date to carry metrics, and a date-segmented report drops entities with no
  // data in the window, so a brand-new or long-paused group would come back missing. Every
  // asset-group write resolves its target through here, which doubles as the check that the
  // group belongs to the selected customer.
  public Optional<GoogleAssetGroupDetailDto> findAssetGroup(
      String accessToken, String customerId, String loginCustomerId, String assetGroupId) {
    for (JsonNode row :
        searchStream(
            accessToken, customerId, loginCustomerId, assetGroupDetailGaql(assetGroupId))) {
      JsonNode group = row.path("assetGroup");
      JsonNode campaign = row.path("campaign");
      List<String> reasons = new ArrayList<>();
      for (JsonNode reason : group.path("primaryStatusReasons")) {
        reasons.add(reason.asText());
      }
      List<String> finalUrls = new ArrayList<>();
      for (JsonNode url : group.path("finalUrls")) {
        finalUrls.add(url.asText());
      }
      log.debug("Customer {} resolved asset group {}", customerId, assetGroupId);
      return Optional.of(
          new GoogleAssetGroupDetailDto(
              group.path("resourceName").asText(null),
              group.path("id").asText(null),
              group.path("name").asText(null),
              group.path("status").asText(null),
              finalUrls,
              group.path("path1").asText(null),
              group.path("path2").asText(null),
              group.path("primaryStatus").asText(null),
              reasons,
              group.path("adStrength").asText(null),
              campaign.path("id").asText(null),
              campaign.path("name").asText(null),
              campaign.path("advertisingChannelType").asText(null),
              campaign.path("status").asText(null),
              campaign.path("brandGuidelinesEnabled").asBoolean(false)));
    }
    log.debug("Customer {} has no asset group {}", customerId, assetGroupId);
    return Optional.empty();
  }

  // Every asset linked to one group, with each asset's own payload, because an asset edit
  // diffs on asset identity — the text, the image dimensions, the YouTube id — and not on
  // link ids. assetGroupAssetsGaql cannot serve: it selects only the text and is bounded by
  // clampLimit, whose default of 100 rows would silently truncate a full asset group.
  public List<GoogleAssetLinkDto> listAssetGroupAssetLinks(
      String accessToken, String customerId, String loginCustomerId, String assetGroupId) {
    List<GoogleAssetLinkDto> links = new ArrayList<>();
    for (JsonNode row :
        searchStream(
            accessToken, customerId, loginCustomerId, assetGroupAssetLinksGaql(assetGroupId))) {
      JsonNode link = row.path("assetGroupAsset");
      JsonNode asset = row.path("asset");
      JsonNode fullSize = asset.path("imageAsset").path("fullSize");
      List<String> reasons = new ArrayList<>();
      for (JsonNode reason : link.path("primaryStatusReasons")) {
        reasons.add(reason.asText());
      }
      links.add(
          new GoogleAssetLinkDto(
              row.path("assetGroup").path("id").asText(null),
              asset.path("id").asText(null),
              asset.path("resourceName").asText(null),
              link.path("fieldType").asText(null),
              link.path("status").asText(null),
              link.path("primaryStatus").asText(null),
              reasons,
              asset.path("type").asText(null),
              asset.path("name").asText(null),
              asset.path("textAsset").path("text").asText(null),
              fullSize.hasNonNull("widthPixels") ? fullSize.get("widthPixels").asInt() : null,
              fullSize.hasNonNull("heightPixels") ? fullSize.get("heightPixels").asInt() : null,
              fullSize.path("url").asText(null),
              asset.path("youtubeVideoAsset").path("youtubeVideoId").asText(null),
              asset.path("callToActionAsset").path("callToAction").asText(null)));
    }
    log.debug("Asset group {} has {} linked assets", assetGroupId, links.size());
    return links;
  }

  // The assets inside those groups. Attributes only — asset_group_asset carries no
  // metrics, just each asset's slot and whether it is eligible to serve.
  public List<GoogleAssetGroupAssetDto> listAssetGroupAssets(
      String accessToken, String customerId, String loginCustomerId, GoogleAssetGroupQuery query) {
    List<GoogleAssetGroupAssetDto> assets = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, assetGroupAssetsGaql(query))) {
      JsonNode link = row.path("assetGroupAsset");
      JsonNode asset = row.path("asset");
      List<String> reasons = new ArrayList<>();
      for (JsonNode reason : link.path("primaryStatusReasons")) {
        reasons.add(reason.asText());
      }
      assets.add(
          new GoogleAssetGroupAssetDto(
              row.path("assetGroup").path("id").asText(null),
              asset.path("id").asText(null),
              link.path("fieldType").asText(null),
              link.path("status").asText(null),
              link.path("primaryStatus").asText(null),
              reasons,
              asset.path("textAsset").path("text").asText(null)));
    }
    log.debug("Customer {} returned {} asset group assets", customerId, assets.size());
    return assets;
  }

  // Search themes and audience signals — what PMax has been told to look for.
  public List<GoogleAssetGroupSignalDto> listAssetGroupSignals(
      String accessToken, String customerId, String loginCustomerId, GoogleAssetGroupQuery query) {
    List<GoogleAssetGroupSignalDto> signals = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, assetGroupSignalsGaql(query))) {
      JsonNode signal = row.path("assetGroupSignal");
      String theme = signal.path("searchTheme").path("text").asText(null);
      String audience = signal.path("audience").path("audience").asText(null);
      signals.add(
          new GoogleAssetGroupSignalDto(
              row.path("assetGroup").path("id").asText(null),
              theme != null ? "SEARCH_THEME" : "AUDIENCE",
              theme != null ? theme : audience,
              signal.path("resourceName").asText(null)));
    }
    log.debug("Customer {} returned {} asset group signals", customerId, signals.size());
    return signals;
  }

  // How the account counts conversions: every action's category, counting type,
  // attribution model and value settings. This is configuration, not performance,
  // so it carries no date filter — conversionActionStats supplies the volume that
  // makes a misconfiguration visible.
  public List<GoogleConversionActionDto> listConversionActions(
      String accessToken, String customerId, String loginCustomerId, boolean includeRemoved) {
    List<GoogleConversionActionDto> actions = new ArrayList<>();
    for (JsonNode row :
        searchStream(
            accessToken, customerId, loginCustomerId, conversionActionsGaql(includeRemoved))) {
      JsonNode action = row.path("conversionAction");
      JsonNode valueSettings = action.path("valueSettings");
      actions.add(
          new GoogleConversionActionDto(
              action.path("id").asText(null),
              action.path("name").asText(null),
              action.path("category").asText(null),
              action.path("type").asText(null),
              action.path("status").asText(null),
              nullableBoolean(action, "primaryForGoal"),
              action.path("countingType").asText(null),
              action.path("attributionModelSettings").path("attributionModel").asText(null),
              // default_value is a plain currency amount, not micros.
              valueSettings.hasNonNull("defaultValue")
                  ? Math.round(valueSettings.path("defaultValue").asDouble() * 100)
                  : null,
              valueSettings.path("defaultCurrencyCode").asText(null),
              nullableBoolean(valueSettings, "alwaysUseDefaultValue"),
              nullableInt(action, "clickThroughLookbackWindowDays"),
              nullableInt(action, "viewThroughLookbackWindowDays"),
              nullableBoolean(action, "includeInConversionsMetric"),
              action.path("origin").asText(null)));
    }
    log.debug("Customer {} has {} conversion actions", customerId, actions.size());
    return actions;
  }

  // Conversions per action over a date range. Segmenting the account report by
  // conversion action is the only way to get this — the counts are not fields of
  // the conversion_action resource.
  public List<GoogleConversionActionStatsDto> conversionActionStats(
      String accessToken,
      String customerId,
      String loginCustomerId,
      GoogleConversionActionQuery query) {
    List<GoogleConversionActionStatsDto> stats = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, conversionActionStatsGaql(query))) {
      JsonNode segments = row.path("segments");
      JsonNode metrics = row.path("metrics");
      stats.add(
          new GoogleConversionActionStatsDto(
              segments.path("conversionActionName").asText(null),
              segments.path("conversionActionCategory").asText(null),
              metrics.path("allConversions").asDouble(0),
              // all_conversions_value is in currency units, like conversions_value.
              Math.round(metrics.path("allConversionsValue").asDouble(0) * 100)));
    }
    log.debug("Customer {} reported volume for {} conversion actions", customerId, stats.size());
    return stats;
  }

  // What the account as a whole optimizes towards. A conversion action only feeds
  // Smart Bidding and the conversions column when the goal covering its category and
  // origin is biddable, so an action can be ENABLED and primary and still be ignored.
  public List<GoogleConversionGoalDto> listCustomerConversionGoals(
      String accessToken, String customerId, String loginCustomerId) {
    String gaql =
        "SELECT customer_conversion_goal.category, customer_conversion_goal.origin,"
            + " customer_conversion_goal.biddable FROM customer_conversion_goal";
    List<GoogleConversionGoalDto> goals = new ArrayList<>();
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, gaql)) {
      JsonNode goal = row.path("customerConversionGoal");
      goals.add(
          new GoogleConversionGoalDto(
              goal.path("category").asText(null),
              goal.path("origin").asText(null),
              goal.path("biddable").asBoolean(false)));
    }
    log.debug("Customer {} has {} account-level conversion goals", customerId, goals.size());
    return goals;
  }

  // The campaign's own goal rows plus the level it currently reads goals from. Google
  // keeps the rows materialized even while the campaign inherits account goals, so an
  // empty list means the campaign type has no goals at all, not that none are set.
  public GoogleCampaignGoalsDto listCampaignConversionGoals(
      String accessToken, String customerId, String loginCustomerId, String campaignId) {
    String id = sanitizeId(campaignId);
    String goalsGaql =
        "SELECT campaign_conversion_goal.category, campaign_conversion_goal.origin,"
            + " campaign_conversion_goal.biddable FROM campaign_conversion_goal"
            + " WHERE campaign.id = "
            + id;
    List<GoogleConversionGoalDto> goals = new ArrayList<>();
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, goalsGaql)) {
      JsonNode goal = row.path("campaignConversionGoal");
      goals.add(
          new GoogleConversionGoalDto(
              goal.path("category").asText(null),
              goal.path("origin").asText(null),
              goal.path("biddable").asBoolean(false)));
    }
    String configGaql =
        "SELECT conversion_goal_campaign_config.goal_config_level"
            + " FROM conversion_goal_campaign_config WHERE campaign.id = "
            + id;
    String level = null;
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, configGaql)) {
      level = row.path("conversionGoalCampaignConfig").path("goalConfigLevel").asText(null);
    }
    log.debug(
        "Campaign {} of customer {} has {} conversion goals at level {}",
        campaignId,
        customerId,
        goals.size(),
        level);
    return new GoogleCampaignGoalsDto(campaignId, level, goals);
  }

  // Keyword ideas with search volume. Not a GAQL report — KeywordPlanIdeaService is
  // its own RPC, so this skips searchStream while reusing the same auth headers.
  // First page only: the API will happily return 10k ideas, which no tool result
  // should carry, so pageSize does the capping and nextPageToken is ignored.
  public List<GoogleKeywordIdeaDto> generateKeywordIdeas(
      String accessToken, String customerId, String loginCustomerId, GoogleKeywordIdeaSpec spec) {
    boolean hasKeywords = spec.keywords() != null && !spec.keywords().isEmpty();
    boolean hasUrl = spec.pageUrl() != null && !spec.pageUrl().isBlank();
    if (!hasKeywords && !hasUrl) {
      throw new GoogleAdsApiException(
          "Keyword ideas need at least one seed keyword or a page url", 400, "INVALID_ARGUMENT");
    }
    ObjectNode body = objectMapper.createObjectNode();
    // `seed` is a union field: exactly one of the seed shapes may be set.
    if (hasKeywords && hasUrl) {
      ObjectNode seed = body.putObject("keywordAndUrlSeed");
      seed.put("url", spec.pageUrl());
      ArrayNode seedKeywords = seed.putArray("keywords");
      spec.keywords().forEach(seedKeywords::add);
    } else if (hasKeywords) {
      ArrayNode seedKeywords = body.putObject("keywordSeed").putArray("keywords");
      spec.keywords().forEach(seedKeywords::add);
    } else {
      body.putObject("urlSeed").put("url", spec.pageUrl());
    }
    if (spec.geoTargetIds() != null && !spec.geoTargetIds().isEmpty()) {
      ArrayNode geoTargets = body.putArray("geoTargetConstants");
      for (String geoTargetId : spec.geoTargetIds()) {
        geoTargets.add("geoTargetConstants/" + sanitizeId(geoTargetId));
      }
    }
    if (spec.languageId() != null && !spec.languageId().isBlank()) {
      body.put("language", "languageConstants/" + sanitizeId(spec.languageId()));
    }
    body.put("keywordPlanNetwork", "GOOGLE_SEARCH");
    body.put("includeAdultKeywords", false);
    body.put("pageSize", clampLimit(spec.limit()));

    JsonNode response =
        post(
            apiBase() + "/customers/" + sanitizeId(customerId) + ":generateKeywordIdeas",
            accessToken,
            loginCustomerId,
            body,
            "Google Ads generateKeywordIdeas",
            GoogleAdsRequestContext.of(customerId, loginCustomerId, body));
    List<GoogleKeywordIdeaDto> ideas = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      JsonNode metrics = result.path("keywordIdeaMetrics");
      ideas.add(
          new GoogleKeywordIdeaDto(
              result.path("text").asText(null),
              metrics.hasNonNull("avgMonthlySearches")
                  ? metrics.path("avgMonthlySearches").asLong()
                  : null,
              metrics.path("competition").asText(null),
              metrics.hasNonNull("competitionIndex")
                  ? metrics.path("competitionIndex").asInt()
                  : null,
              metrics.hasNonNull("lowTopOfPageBidMicros")
                  ? microsToCents(metrics.path("lowTopOfPageBidMicros").asLong())
                  : null,
              metrics.hasNonNull("highTopOfPageBidMicros")
                  ? microsToCents(metrics.path("highTopOfPageBidMicros").asLong())
                  : null));
    }
    log.debug("Customer {} received {} keyword ideas", customerId, ideas.size());
    return ideas;
  }

  // --- GAQL builders ----------------------------------------------------------
  // Kept static and free of HTTP so the query text itself is unit-testable.

  static String insightsGaql(GoogleInsightsQuery query) {
    String level = query.level();
    StringBuilder gaql = new StringBuilder("SELECT ");
    switch (level) {
      case LEVEL_KEYWORD ->
          gaql.append(
              "ad_group_criterion.criterion_id, ad_group_criterion.keyword.text,"
                  + " ad_group_criterion.keyword.match_type, ad_group.id, ad_group.name,"
                  + " campaign.id, campaign.name, ");
      case LEVEL_AD_GROUP ->
          gaql.append("ad_group.id, ad_group.name, campaign.id, campaign.name, ");
      case LEVEL_CAMPAIGN -> gaql.append("campaign.id, campaign.name, ");
      // Account level reports metrics only — there is no entity to name.
      default -> {}
    }
    for (String segment : segmentsOf(query)) {
      gaql.append(segmentField(segment)).append(", ");
    }
    gaql.append(METRIC_FIELDS);
    if (query.includeImpressionShare()) {
      boolean budgetLostAvailable = LEVEL_ACCOUNT.equals(level) || LEVEL_CAMPAIGN.equals(level);
      gaql.append(", ")
          .append(
              budgetLostAvailable ? IMPRESSION_SHARE_FIELDS : IMPRESSION_SHARE_FIELDS_NO_BUDGET);
    }
    gaql.append(" FROM ").append(insightsResource(level)).append(" WHERE ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    if (!LEVEL_ACCOUNT.equals(level)) {
      gaql.append(" AND campaign.status != 'REMOVED'");
      if (query.campaignId() != null) {
        gaql.append(" AND campaign.id = ").append(sanitizeId(query.campaignId()));
      }
      if (query.adGroupId() != null && !LEVEL_CAMPAIGN.equals(level)) {
        gaql.append(" AND ad_group.id = ").append(sanitizeId(query.adGroupId()));
      }
    }
    if (isRowCapped(query)) {
      gaql.append(" ORDER BY metrics.impressions DESC LIMIT ").append(clampLimit(query.limit()));
    }
    return gaql.toString();
  }

  static String geoInsightsGaql(GoogleGeoInsightsQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT geographic_view.country_criterion_id, geographic_view.location_type,"
                + " campaign.id, campaign.name, ");
    gaql.append(METRIC_FIELDS).append(" FROM geographic_view WHERE ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    if (query.campaignId() != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(query.campaignId()));
    }
    return gaql.append(" ORDER BY metrics.cost_micros DESC LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  static String geoTargetConstantsGaql(List<String> locationIds) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT geo_target_constant.id, geo_target_constant.name,"
                + " geo_target_constant.canonical_name"
                + " FROM geo_target_constant WHERE geo_target_constant.id IN (");
    for (int i = 0; i < locationIds.size(); i++) {
      gaql.append(i == 0 ? "" : ", ").append(sanitizeId(locationIds.get(i)));
    }
    return gaql.append(")").toString();
  }

  static String targetingCriteriaGaql(String campaignId) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT campaign_criterion.criterion_id, campaign_criterion.type,"
                + " campaign_criterion.negative, campaign_criterion.bid_modifier,"
                + " campaign_criterion.status,"
                + " campaign_criterion.location.geo_target_constant,"
                + " campaign_criterion.language.language_constant, campaign.id"
                + " FROM campaign_criterion"
                + " WHERE campaign_criterion.type IN ('LOCATION', 'LANGUAGE')"
                + " AND campaign_criterion.status != 'REMOVED'");
    if (campaignId != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(campaignId));
    }
    return gaql.toString();
  }

  static String audienceInsightsGaql(GoogleAudienceInsightsQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT ad_group_criterion.criterion_id, ad_group_criterion.type,"
                + " ad_group_criterion.user_list.user_list,"
                + " ad_group_criterion.user_interest.user_interest_category,"
                + " ad_group.id, ad_group.name, campaign.id, campaign.name, ");
    gaql.append(METRIC_FIELDS).append(" FROM ad_group_audience_view WHERE ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    if (query.campaignId() != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(query.campaignId()));
    }
    if (query.adGroupId() != null) {
      gaql.append(" AND ad_group.id = ").append(sanitizeId(query.adGroupId()));
    }
    return gaql.append(" ORDER BY metrics.cost_micros DESC LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  static String assetGroupsGaql(GoogleAssetGroupQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT asset_group.id, asset_group.name, asset_group.status,"
                + " asset_group.ad_strength, asset_group.primary_status,"
                + " asset_group.primary_status_reasons, campaign.id, campaign.name, ");
    gaql.append(METRIC_FIELDS).append(" FROM asset_group WHERE ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    appendAssetGroupFilters(gaql, query, true);
    return gaql.append(" ORDER BY metrics.cost_micros DESC LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  // No date filter and no metrics, so an asset group with no impressions yet is still
  // found — the whole point of not reusing assetGroupsGaql. campaign is an attributed
  // resource here, which is what makes its channel type and brand-guidelines flag readable
  // in the same row.
  static String assetGroupDetailGaql(String assetGroupId) {
    return "SELECT asset_group.resource_name, asset_group.id, asset_group.name,"
        + " asset_group.status, asset_group.final_urls, asset_group.path1, asset_group.path2,"
        + " asset_group.primary_status, asset_group.primary_status_reasons,"
        + " asset_group.ad_strength,"
        + " campaign.id, campaign.name, campaign.advertising_channel_type, campaign.status,"
        + " campaign.brand_guidelines_enabled"
        + " FROM asset_group WHERE asset_group.id = "
        + sanitizeId(assetGroupId)
        + " LIMIT 1";
  }

  // Every asset kind's payload for one group. The limit is well above the ~96 links a full
  // asset group can hold rather than clampLimit's 100 default, and the caller treats a
  // result of exactly this size as a truncated view rather than diffing against it.
  public static final int MAX_ASSET_LINK_ROWS = 500;

  static String assetGroupAssetLinksGaql(String assetGroupId) {
    return "SELECT asset_group_asset.field_type, asset_group_asset.status,"
        + " asset_group_asset.primary_status, asset_group_asset.primary_status_reasons,"
        + " asset_group.id, asset.id, asset.resource_name, asset.type, asset.name,"
        + " asset.text_asset.text,"
        + " asset.image_asset.full_size.width_pixels,"
        + " asset.image_asset.full_size.height_pixels, asset.image_asset.full_size.url,"
        + " asset.youtube_video_asset.youtube_video_id,"
        + " asset.call_to_action_asset.call_to_action"
        + " FROM asset_group_asset WHERE asset_group.id = "
        + sanitizeId(assetGroupId)
        + " AND asset_group_asset.status != 'REMOVED'"
        + " ORDER BY asset_group_asset.field_type LIMIT "
        + MAX_ASSET_LINK_ROWS;
  }

  // Attributes only: asset_group_asset has no metrics, so no date filter either.
  // No performance_label either — Google removed that field for Performance Max, so
  // selecting it fails outright; primary_status is what per-asset health reads from now.
  static String assetGroupAssetsGaql(GoogleAssetGroupQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT asset_group_asset.field_type, asset_group_asset.status,"
                + " asset_group_asset.primary_status,"
                + " asset_group_asset.primary_status_reasons, asset_group.id, asset.id,"
                + " asset.text_asset.text"
                + " FROM asset_group_asset WHERE asset_group_asset.status != 'REMOVED'");
    appendAssetGroupFilters(gaql, query, true);
    return gaql.append(" ORDER BY asset_group.id LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  static String assetGroupSignalsGaql(GoogleAssetGroupQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT asset_group_signal.resource_name,"
                + " asset_group_signal.search_theme.text, asset_group_signal.audience.audience,"
                + " asset_group.id"
                + " FROM asset_group_signal");
    appendAssetGroupFilters(gaql, query, false);
    return gaql.append(" ORDER BY asset_group.id LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  // `hasWhere` says whether a WHERE clause is already open, so a report with no
  // mandatory condition of its own still produces valid GAQL when a filter is set.
  private static void appendAssetGroupFilters(
      StringBuilder gaql, GoogleAssetGroupQuery query, boolean hasWhere) {
    boolean open = hasWhere;
    if (query.campaignId() != null) {
      gaql.append(open ? " AND " : " WHERE ")
          .append("campaign.id = ")
          .append(sanitizeId(query.campaignId()));
      open = true;
    }
    if (query.assetGroupId() != null) {
      gaql.append(open ? " AND " : " WHERE ")
          .append("asset_group.id = ")
          .append(sanitizeId(query.assetGroupId()));
    }
  }

  static String conversionActionsGaql(boolean includeRemoved) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT conversion_action.id, conversion_action.name, conversion_action.category,"
                + " conversion_action.type, conversion_action.status,"
                + " conversion_action.primary_for_goal, conversion_action.counting_type,"
                + " conversion_action.attribution_model_settings.attribution_model,"
                + " conversion_action.value_settings.default_value,"
                + " conversion_action.value_settings.default_currency_code,"
                + " conversion_action.value_settings.always_use_default_value,"
                + " conversion_action.click_through_lookback_window_days,"
                + " conversion_action.view_through_lookback_window_days,"
                + " conversion_action.include_in_conversions_metric, conversion_action.origin"
                + " FROM conversion_action");
    if (!includeRemoved) {
      gaql.append(" WHERE conversion_action.status != 'REMOVED'");
    }
    return gaql.append(" ORDER BY conversion_action.name").toString();
  }

  static String conversionActionStatsGaql(GoogleConversionActionQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT segments.conversion_action_name, segments.conversion_action_category,"
                + " metrics.all_conversions, metrics.all_conversions_value"
                + " FROM customer WHERE ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    return gaql.append(" ORDER BY metrics.all_conversions DESC").toString();
  }

  static String keywordsGaql(String campaignId, String adGroupId) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT ad_group_criterion.criterion_id, ad_group_criterion.keyword.text,"
                + " ad_group_criterion.keyword.match_type, ad_group_criterion.status,"
                + " ad_group_criterion.negative, ad_group_criterion.system_serving_status,"
                + " ad_group_criterion.primary_status, ad_group_criterion.primary_status_reasons,"
                + " ad_group_criterion.quality_info.quality_score,"
                + " ad_group_criterion.effective_cpc_bid_micros,"
                + " ad_group.id, ad_group.name, campaign.id"
                + " FROM ad_group_criterion"
                + " WHERE ad_group_criterion.type = 'KEYWORD'"
                + " AND ad_group_criterion.status != 'REMOVED'");
    if (campaignId != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(campaignId));
    }
    if (adGroupId != null) {
      gaql.append(" AND ad_group.id = ").append(sanitizeId(adGroupId));
    }
    return gaql.append(" ORDER BY ad_group.id").toString();
  }

  static String searchTermsGaql(GoogleSearchTermQuery query) {
    StringBuilder gaql =
        new StringBuilder(
            "SELECT search_term_view.search_term, search_term_view.status,"
                + " segments.keyword.info.text, segments.keyword.info.match_type,"
                + " ad_group.id, campaign.id, metrics.impressions, metrics.clicks,"
                + " metrics.cost_micros, metrics.ctr, metrics.conversions"
                + " FROM search_term_view WHERE ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    if (query.campaignId() != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(query.campaignId()));
    }
    if (query.adGroupId() != null) {
      gaql.append(" AND ad_group.id = ").append(sanitizeId(query.adGroupId()));
    }
    return gaql.append(" ORDER BY metrics.impressions DESC LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  private static String insightsResource(String level) {
    return switch (level) {
      case LEVEL_KEYWORD -> "keyword_view";
      case LEVEL_AD_GROUP -> "ad_group";
      case LEVEL_CAMPAIGN -> "campaign";
      default -> "customer";
    };
  }

  // Only the levels that can explode into thousands of rows carry a LIMIT. Public
  // because the tools need it to tell the caller a result was capped.
  public static boolean isRowCapped(String level) {
    return LEVEL_KEYWORD.equals(level) || LEVEL_AD_GROUP.equals(level);
  }

  // A segmented query returns one row per entity *per segment value* — 24 rows per
  // entity for hour alone — so it is capped at every level, including the two that
  // are otherwise uncapped.
  public static boolean isRowCapped(GoogleInsightsQuery query) {
    return isRowCapped(query.level()) || !segmentsOf(query).isEmpty();
  }

  private static List<String> segmentsOf(GoogleInsightsQuery query) {
    return query.segments() == null ? List.of() : query.segments();
  }

  // GAQL field for a segment name. Unknown names never reach the query text.
  private static String segmentField(String segment) {
    if (!SEGMENTS.contains(segment)) {
      throw new GoogleAdsApiException(
          "Unsupported segment: " + segment + ". Supported: " + String.join(", ", SEGMENTS),
          400,
          "INVALID_ARGUMENT");
    }
    return "segments." + segment;
  }

  private static Double nullableDouble(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.path(field).asDouble() : null;
  }

  private static Boolean nullableBoolean(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.path(field).asBoolean() : null;
  }

  private static Integer nullableInt(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.path(field).asInt() : null;
  }

  // The REST response camelCases GAQL field names, so segments.day_of_week comes
  // back as segments.dayOfWeek.
  private static String segmentJsonKey(String segment) {
    return switch (segment) {
      case "day_of_week" -> "dayOfWeek";
      case "ad_network_type" -> "adNetworkType";
      default -> segment;
    };
  }

  public static int clampLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_REPORT_ROWS;
    }
    return Math.min(limit, MAX_REPORT_ROWS);
  }

  private static void appendDateFilter(
      StringBuilder gaql, String datePreset, String since, String until) {
    if (datePreset != null) {
      gaql.append("segments.date DURING ").append(sanitizeKeyword(datePreset));
    } else {
      gaql.append("segments.date BETWEEN '")
          .append(sanitizeDate(since))
          .append("' AND '")
          .append(sanitizeDate(until))
          .append("'");
    }
  }

  private static String insightsEntityId(
      String level, JsonNode campaign, JsonNode adGroup, JsonNode criterion) {
    return switch (level) {
      case LEVEL_KEYWORD -> criterion.path("criterionId").asText(null);
      case LEVEL_AD_GROUP -> adGroup.path("id").asText(null);
      case LEVEL_CAMPAIGN -> campaign.path("id").asText(null);
      default -> null;
    };
  }

  private static String insightsEntityName(
      String level, JsonNode campaign, JsonNode adGroup, JsonNode criterion) {
    return switch (level) {
      case LEVEL_KEYWORD -> criterion.path("keyword").path("text").asText(null);
      case LEVEL_AD_GROUP -> adGroup.path("name").asText(null);
      case LEVEL_CAMPAIGN -> campaign.path("name").asText(null);
      default -> null;
    };
  }

  // --- Mutations -------------------------------------------------------------

  // Raises the account-level spending limit — the total ceiling above every campaign
  // budget on invoiced accounts. Google models this as a *proposal* rather than a
  // direct update, so the operation is a create against accountBudgetProposals even
  // though it edits an existing budget.
  //
  // Deliberately never sends proposedSpendingLimitType=INFINITE: removing the account's
  // hard spend ceiling is not something the agent may do on the user's behalf.
  //
  // Whether the proposal applies at once or waits for approval depends on the billing
  // setup, so callers must re-read the budget afterwards rather than treating a 2xx as
  // "the limit is now N".
  public void updateAccountBudgetSpendingLimit(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String accountBudgetResourceName,
      long amountMicros) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("proposalType", "UPDATE");
    create.put("accountBudget", accountBudgetResourceName);
    create.put("proposedSpendingLimitMicros", amountMicros);
    mutate(accessToken, customerId, loginCustomerId, "accountBudgetProposals", operation);
    log.debug(
        "Proposed account budget limit {} micros for {}", amountMicros, accountBudgetResourceName);
  }

  public void updateCampaignBudget(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String budgetResourceName,
      long amountMicros) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", budgetResourceName);
    update.put("amountMicros", amountMicros);
    operation.put("updateMask", "amount_micros");
    mutate(accessToken, customerId, loginCustomerId, "campaignBudgets", operation);
  }

  // Campaign-level bidding: the Max CPC limit of Maximize Clicks, the target CPA of
  // Maximize Conversions, and the switch between the three strategies. `strategy` is
  // TARGET_SPEND, MANUAL_CPC or MAXIMIZE_CONVERSIONS. Switching and staying build the
  // same operation, because the strategy is only ever reached through one of its own
  // leaf fields — see campaignBiddingUpdateOperation.
  public void updateCampaignBidding(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      GoogleCampaignBiddingSpec spec) {
    String resourceName =
        "customers/" + sanitizeId(customerId) + "/campaigns/" + sanitizeId(campaignId);
    ObjectNode operation = campaignBiddingUpdateOperation(objectMapper, resourceName, spec);
    log.debug(
        "Updating bidding of campaign {} for customer {}: strategy={} ceilingMicros={}"
            + " targetCpaMicros={} targetRoas={}",
        campaignId,
        customerId,
        spec.strategy(),
        spec.cpcBidCeilingMicros(),
        spec.targetCpaMicros(),
        spec.targetRoas());
    mutate(accessToken, customerId, loginCustomerId, "campaigns", operation);
  }

  // "Maximize clicks" is Campaign.target_spend — Campaign has no maximize_clicks field,
  // and TargetSpend.targetSpendMicros is deprecated, so cpcBidCeilingMicros is the only
  // value ever written. Every updateMask path here is a leaf: Google rejects a mask that
  // names a message carrying subfields (fieldMaskError.FIELD_HAS_SUBFIELDS), so
  // `target_spend`, `maximize_conversions` and `manual_cpc` can never be masked
  // themselves, not even when switching the campaign onto that strategy. Masking a leaf
  // under the strategy is what selects the strategy: the oneof follows the field that
  // gets written. A masked path whose value is absent from the body is cleared, which is
  // how the ceiling and the target CPA are removed — and why switching onto a strategy
  // without a value starts it with none. manual_cpc has exactly one leaf,
  // enhanced_cpc_enabled, and enhanced CPC is sunset, so false is both its real value
  // and the only vehicle for the switch.
  static ObjectNode campaignBiddingUpdateOperation(
      ObjectMapper objectMapper, String campaignResourceName, GoogleCampaignBiddingSpec spec) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", campaignResourceName);
    if (STRATEGY_MANUAL_CPC.equals(spec.strategy())) {
      update.putObject("manualCpc").put("enhancedCpcEnabled", false);
      operation.put("updateMask", "manual_cpc.enhanced_cpc_enabled");
      return operation;
    }
    if (STRATEGY_MAXIMIZE_CONVERSIONS.equals(spec.strategy())) {
      ObjectNode maximizeConversions = update.putObject("maximizeConversions");
      if (spec.targetCpaMicros() != null) {
        maximizeConversions.put("targetCpaMicros", spec.targetCpaMicros());
      }
      // cpc_bid_ceiling_micros and cpc_bid_floor_micros of MaximizeConversions are
      // mutable for portfolio strategies only, so target_cpa_micros is the only leaf.
      operation.put("updateMask", "maximize_conversions.target_cpa_micros");
      return operation;
    }
    if (STRATEGY_MAXIMIZE_CONVERSION_VALUE.equals(spec.strategy())) {
      ObjectNode maximizeConversionValue = update.putObject("maximizeConversionValue");
      if (spec.targetRoas() != null) {
        // A bare ratio: 4.0 is four units of value per unit of spend, not micros and not a
        // percentage. Sending 400 here would ask Google for a 40000% return and stop the
        // campaign spending entirely.
        maximizeConversionValue.put("targetRoas", spec.targetRoas());
      }
      // Same rule as MaximizeConversions: the cpc bid ceiling and floor are mutable for
      // portfolio strategies only, so target_roas is the one leaf — and therefore also the
      // only vehicle for switching onto the strategy at all.
      operation.put("updateMask", "maximize_conversion_value.target_roas");
      return operation;
    }
    ObjectNode targetSpend = update.putObject("targetSpend");
    if (spec.cpcBidCeilingMicros() != null) {
      targetSpend.put("cpcBidCeilingMicros", spec.cpcBidCeilingMicros());
    }
    operation.put("updateMask", "target_spend.cpc_bid_ceiling_micros");
    return operation;
  }

  // Campaign-level conversion goals. Google materializes one row per (category, origin)
  // and the API only supports updating them — never create or remove — so this masks
  // `biddable` on the rows the caller names and sends them in one mutate. Updating any
  // row also flips the campaign's goal_config_level to CAMPAIGN by itself, which is
  // what "Use campaign-specific goal settings" means in the UI.
  public void updateCampaignConversionGoals(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      Map<String, Boolean> biddableByGoalKey) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (Map.Entry<String, Boolean> entry : biddableByGoalKey.entrySet()) {
      // A goal whose category or origin is Google's UNKNOWN sentinel has no resource
      // name, and one such operation fails the entire mutate with BAD_RESOURCE_ID.
      if (!isAddressableGoalKey(entry.getKey())) {
        log.debug(
            "Skipping unaddressable conversion goal {} of campaign {}", entry.getKey(), campaignId);
        continue;
      }
      ObjectNode operation = operations.addObject();
      ObjectNode update = operation.putObject("update");
      update.put(
          "resourceName",
          "customers/"
              + sanitizeId(customerId)
              + "/campaignConversionGoals/"
              + sanitizeId(campaignId)
              + "~"
              + entry.getKey().replace(':', '~'));
      update.put("biddable", entry.getValue());
      operation.put("updateMask", "biddable");
    }
    log.debug(
        "Updating {} conversion goals of campaign {} for customer {}: {}",
        operations.size(),
        campaignId,
        customerId,
        biddableByGoalKey);
    if (operations.isEmpty()) {
      return;
    }
    mutateAll(accessToken, customerId, loginCustomerId, "campaignConversionGoals", operations);
  }

  private static boolean isAddressableGoalKey(String goalKey) {
    if (goalKey == null) {
      return false;
    }
    String[] parts = goalKey.split(":");
    if (parts.length != 2) {
      return false;
    }
    for (String part : parts) {
      if (part.isBlank() || "UNKNOWN".equals(part) || "UNSPECIFIED".equals(part)) {
        return false;
      }
    }
    return true;
  }

  // Moves a campaign back to the account's goals. Only ever called with CUSTOMER: the
  // CAMPAIGN direction happens on its own when a campaign goal is updated.
  public void setCampaignGoalConfigLevel(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      String goalConfigLevel) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put(
        "resourceName",
        "customers/"
            + sanitizeId(customerId)
            + "/conversionGoalCampaignConfigs/"
            + sanitizeId(campaignId));
    update.put("goalConfigLevel", goalConfigLevel);
    operation.put("updateMask", "goal_config_level");
    log.debug(
        "Setting goal config level of campaign {} for customer {} to {}",
        campaignId,
        customerId,
        goalConfigLevel);
    mutate(accessToken, customerId, loginCustomerId, "conversionGoalCampaignConfigs", operation);
  }

  // Edits one conversion action's own settings — how it counts, whether it is primary,
  // what a conversion is worth, how far back a click or a view counts. This is the
  // resource campaignConversionGoals only groups: changing the goal decides which
  // campaigns bid towards a category, changing the action decides what the account
  // records in the first place.
  public void updateConversionAction(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String conversionActionId,
      GoogleConversionActionUpdateSpec spec) {
    String resourceName =
        "customers/"
            + sanitizeId(customerId)
            + "/conversionActions/"
            + sanitizeId(conversionActionId);
    ObjectNode operation = conversionActionUpdateOperation(objectMapper, resourceName, spec);
    log.debug(
        "Updating conversion action {} of customer {} with mask {}",
        conversionActionId,
        customerId,
        operation.path("updateMask").asText());
    mutate(accessToken, customerId, loginCustomerId, "conversionActions", operation);
  }

  // Only what the caller set reaches the body and the mask; a null field is absent from
  // both, so the action keeps that setting. The three value settings live under one
  // nested object but mask independently, which is what lets a caller set a default value
  // without also claiming to set the currency.
  static ObjectNode conversionActionUpdateOperation(
      ObjectMapper objectMapper, String resourceName, GoogleConversionActionUpdateSpec spec) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", resourceName);
    List<String> mask = new ArrayList<>();
    if (spec.countingType() != null) {
      update.put("countingType", spec.countingType());
      mask.add("counting_type");
    }
    if (spec.primaryForGoal() != null) {
      update.put("primaryForGoal", spec.primaryForGoal());
      mask.add("primary_for_goal");
    }
    if (spec.status() != null) {
      update.put("status", spec.status());
      mask.add("status");
    }
    if (spec.defaultValueCents() != null
        || spec.defaultCurrencyCode() != null
        || spec.alwaysUseDefaultValue() != null) {
      ObjectNode valueSettings = update.putObject("valueSettings");
      if (spec.defaultValueCents() != null) {
        // value_settings.default_value is a plain currency amount, not micros and not
        // minor units — listConversionActions reads it the same way.
        valueSettings.put("defaultValue", spec.defaultValueCents() / 100.0);
        mask.add("value_settings.default_value");
      }
      if (spec.defaultCurrencyCode() != null) {
        valueSettings.put("defaultCurrencyCode", spec.defaultCurrencyCode());
        mask.add("value_settings.default_currency_code");
      }
      if (spec.alwaysUseDefaultValue() != null) {
        valueSettings.put("alwaysUseDefaultValue", spec.alwaysUseDefaultValue());
        mask.add("value_settings.always_use_default_value");
      }
    }
    if (spec.clickThroughLookbackDays() != null) {
      update.put("clickThroughLookbackWindowDays", spec.clickThroughLookbackDays());
      mask.add("click_through_lookback_window_days");
    }
    if (spec.viewThroughLookbackDays() != null) {
      update.put("viewThroughLookbackWindowDays", spec.viewThroughLookbackDays());
      mask.add("view_through_lookback_window_days");
    }
    if (spec.category() != null) {
      update.put("category", spec.category());
      mask.add("category");
    }
    operation.put("updateMask", String.join(",", mask));
    return operation;
  }

  public void setCampaignStatus(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      String status) {
    setStatus(accessToken, customerId, loginCustomerId, "campaigns", campaignId, status);
  }

  public void setAdGroupStatus(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupId,
      String status) {
    setStatus(accessToken, customerId, loginCustomerId, "adGroups", adGroupId, status);
  }

  // Ads live under a composite adGroupAds resource id: {adGroupId}~{adId}.
  public void setAdStatus(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupId,
      String adId,
      String status) {
    setStatus(
        accessToken,
        customerId,
        loginCustomerId,
        "adGroupAds",
        sanitizeId(adGroupId) + "~" + sanitizeId(adId),
        status);
  }

  private void setStatus(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String service,
      String objectId,
      String status) {
    String resourceName = "customers/" + sanitizeId(customerId) + "/" + service + "/" + objectId;
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", resourceName);
    update.put("status", status);
    operation.put("updateMask", "status");
    mutate(accessToken, customerId, loginCustomerId, service, operation);
  }

  public String createCampaignBudget(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String name,
      long amountMicros) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("name", name);
    create.put("amountMicros", amountMicros);
    create.put("deliveryMethod", "STANDARD");
    // Never shared: google_update_budget refuses shared budgets, so budgets we
    // create must stay per-campaign.
    create.put("explicitlyShared", false);
    return mutate(accessToken, customerId, loginCustomerId, "campaignBudgets", operation);
  }

  // Creates a PAUSED Search campaign attached to the given budget.
  public String createSearchCampaign(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String name,
      String budgetResourceName) {
    ObjectNode operation = searchCampaignCreateOperation(objectMapper, name, budgetResourceName);
    log.debug(
        "Creating PAUSED SEARCH campaign '{}' on budget {} for customer {}",
        name,
        budgetResourceName,
        customerId);
    return mutate(accessToken, customerId, loginCustomerId, "campaigns", operation);
  }

  // Simplest safe bidding for a fresh campaign: maximize clicks with no target. That
  // strategy is the TargetSpend field — Campaign has no maximize_clicks field, and
  // MAXIMIZE_CLICKS is only the read-only biddingStrategyType enum read back afterwards.
  // Empty object = no spend cap; TargetSpend.targetSpendMicros is deprecated.
  static ObjectNode searchCampaignCreateOperation(
      ObjectMapper objectMapper, String name, String budgetResourceName) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("name", name);
    create.put("status", "PAUSED");
    create.put("advertisingChannelType", "SEARCH");
    create.put("campaignBudget", budgetResourceName);
    // Required on every campaign created through the API since the EU Political Ads
    // Regulation rollout; omitting it fails with FieldError.REQUIRED. This tool only
    // ever builds product/service Search ads from a landing page, so the declaration is
    // always negative — a political advertiser cannot reach this code path.
    create.put("containsEuPoliticalAdvertising", "DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING");
    create.putObject("targetSpend");
    ObjectNode network = create.putObject("networkSettings");
    network.put("targetGoogleSearch", true);
    network.put("targetSearchNetwork", false);
    network.put("targetContentNetwork", false);
    return operation;
  }

  // Performance Max asset field types this create writes. HEADLINE, LONG_HEADLINE and
  // DESCRIPTION go on the asset group; BUSINESS_NAME goes on the campaign, because brand
  // guidelines are on for campaigns created here and Google then holds the business name and
  // logos at campaign level.
  public static final String FIELD_HEADLINE = "HEADLINE";
  public static final String FIELD_LONG_HEADLINE = "LONG_HEADLINE";
  public static final String FIELD_DESCRIPTION = "DESCRIPTION";
  public static final String FIELD_BUSINESS_NAME = "BUSINESS_NAME";

  // Builds a whole PAUSED Performance Max campaign in one atomic googleAds:mutate: budget,
  // campaign, geo and language criteria, the text assets, the asset group, every asset link
  // and the search themes.
  //
  // Image and video assets are NOT created here. They exist already and arrive as resource
  // names in the two link lists, because an image asset carries its bytes as base64 and a
  // handful of them in one shared body is a request Google answers with 413. The caller
  // decides which links belong to the asset group and which to the campaign, since that
  // split follows the brand-guidelines field types the service layer owns.
  //
  // Atomic on purpose: Google refuses to create an asset group through assetGroups:mutate
  // without the asset links satisfying its minimums in the same request, and one request also
  // means there is no half-built campaign to unwind when Google rejects something.
  public GoogleCreatePmaxResult createPerformanceMaxCampaign(
      String accessToken,
      String customerId,
      String loginCustomerId,
      GoogleCreatePmaxCampaignSpec spec,
      GoogleCampaignBiddingSpec bidding,
      List<String> geoTargetIds,
      List<String> languageIds,
      List<GoogleAssetLinkSpec> assetGroupMediaLinks,
      List<GoogleAssetLinkSpec> campaignMediaLinks) {
    GoogleAdsPmaxPlan plan =
        performanceMaxCreatePlan(
            objectMapper,
            new GoogleAdsTempIds(),
            sanitizeId(customerId),
            spec,
            bidding,
            geoTargetIds,
            languageIds,
            assetGroupMediaLinks,
            campaignMediaLinks);
    log.debug(
        "Creating PAUSED PERFORMANCE_MAX campaign '{}' for customer {} in one googleAds:mutate:"
            + " {} operations, strategy {}, {} asset links",
        spec.name(),
        customerId,
        plan.operations().size(),
        bidding.strategy(),
        plan.assetLinkCount());
    JsonNode response =
        mutateGoogleAds(accessToken, customerId, loginCustomerId, plan.operations());
    JsonNode results = response.path("mutateOperationResponses");
    return new GoogleCreatePmaxResult(
        createdResourceName(results, plan.campaignIndex(), "campaignResult"),
        createdResourceName(results, plan.assetGroupIndex(), "assetGroupResult"),
        plan.assetLinkCount());
  }

  // The bulk response is one entry per operation, in request order, each wrapping the created
  // resource name under a key named after its own operation type.
  private static String createdResourceName(JsonNode results, int index, String resultKey) {
    return results.path(index).path(resultKey).path("resourceName").asText(null);
  }

  // The operation list for a Performance Max create, built rather than sent so it can be
  // asserted without HTTP. Order is load-bearing twice over: Google requires every temporary
  // resource name to be defined by an earlier operation than the one referencing it, and the
  // caller reads the campaign and asset group out of the response by position.
  static GoogleAdsPmaxPlan performanceMaxCreatePlan(
      ObjectMapper objectMapper,
      GoogleAdsTempIds tempIds,
      String customerId,
      GoogleCreatePmaxCampaignSpec spec,
      GoogleCampaignBiddingSpec bidding,
      List<String> geoTargetIds,
      List<String> languageIds,
      List<GoogleAssetLinkSpec> assetGroupMediaLinks,
      List<GoogleAssetLinkSpec> campaignMediaLinks) {
    ArrayNode operations = objectMapper.createArrayNode();

    String budgetResourceName = tempIds.tempResourceName(customerId, "campaignBudgets");
    ObjectNode budget =
        operations.addObject().putObject("campaignBudgetOperation").putObject("create");
    budget.put("resourceName", budgetResourceName);
    budget.put("name", spec.name() + " — Budget");
    budget.put("amountMicros", centsToMicros(spec.dailyBudgetCents()));
    budget.put("deliveryMethod", "STANDARD");
    // Never shared, for the same reason createCampaignBudget never shares one:
    // google_update_budget refuses a shared budget, so a budget created here has to stay
    // per-campaign or it becomes uneditable through the tools.
    budget.put("explicitlyShared", false);

    int campaignIndex = operations.size();
    String campaignResourceName = tempIds.tempResourceName(customerId, "campaigns");
    ObjectNode campaign = operations.addObject().putObject("campaignOperation").putObject("create");
    campaign.put("resourceName", campaignResourceName);
    campaign.put("name", spec.name());
    campaign.put("status", "PAUSED");
    campaign.put("advertisingChannelType", "PERFORMANCE_MAX");
    campaign.put("campaignBudget", budgetResourceName);
    // Required on every campaign created through the API since the EU Political Ads
    // Regulation rollout; omitting it fails with FieldError.REQUIRED. This tool only ever
    // builds product and service campaigns from a landing page, so the declaration is always
    // negative.
    campaign.put("containsEuPoliticalAdvertising", "DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING");
    // Final URL expansion is on by default and lets Google send a click to any page on the
    // site it believes converts better. A campaign built from one landing page should serve
    // that page, so it is opted out here; google_update_campaign_settings is not where that
    // is reversed today, which the tool result says out loud.
    //
    // campaign.url_expansion_opt_out was REMOVED in v22 and sending it is a 400 "Cannot find
    // field", the same class of break as the start_date rename below. The opt-out now lives in
    // campaign.asset_automation_settings under the FINAL_URL_EXPANSION_TEXT_ASSET_AUTOMATION
    // type, so it is written as a setting rather than a boolean.
    ObjectNode urlExpansion = campaign.putArray("assetAutomationSettings").addObject();
    urlExpansion.put("assetAutomationType", "FINAL_URL_EXPANSION_TEXT_ASSET_AUTOMATION");
    urlExpansion.put("assetAutomationStatus", "OPTED_OUT");
    // Brand guidelines default on for Performance Max campaigns created since v21 and move
    // the business name and logos onto the campaign. Written explicitly so this create is
    // honest about which tool edits those three afterwards — google_update_brand_assets —
    // rather than leaving a default to decide it.
    campaign.put("brandGuidelinesEnabled", true);
    // No networkSettings: Performance Max serves across every Google surface and has no
    // network to choose. No shoppingSetting either — a Merchant Center feed would also need a
    // root listing group filter, which nothing here builds.
    if (STRATEGY_MAXIMIZE_CONVERSION_VALUE.equals(bidding.strategy())) {
      ObjectNode maximizeConversionValue = campaign.putObject("maximizeConversionValue");
      if (bidding.targetRoas() != null) {
        // A bare ratio: 4.0 is four units of value per unit of spend, not micros and not a
        // percentage.
        maximizeConversionValue.put("targetRoas", bidding.targetRoas());
      }
    } else {
      ObjectNode maximizeConversions = campaign.putObject("maximizeConversions");
      if (bidding.targetCpaMicros() != null) {
        maximizeConversions.put("targetCpaMicros", bidding.targetCpaMicros());
      }
    }

    for (String geoTargetId : geoTargetIds) {
      ObjectNode criterion =
          operations.addObject().putObject("campaignCriterionOperation").putObject("create");
      criterion.put("campaign", campaignResourceName);
      // negative is immutable and defaults to false, and every location here is targeted, so
      // it is left out entirely — sending the default back is what IMMUTABLE_FIELD complains
      // about.
      criterion
          .putObject("location")
          .put("geoTargetConstant", "geoTargetConstants/" + sanitizeId(geoTargetId));
    }
    for (String languageId : languageIds) {
      ObjectNode criterion =
          operations.addObject().putObject("campaignCriterionOperation").putObject("create");
      criterion.put("campaign", campaignResourceName);
      criterion
          .putObject("language")
          .put("languageConstant", "languageConstants/" + sanitizeId(languageId));
    }

    // Text assets before the links that point at them. Google deduplicates a text asset by
    // its content, so text the account already holds resolves to the existing asset rather
    // than a second copy.
    List<String> headlineAssets =
        appendTextAssetOperations(tempIds, customerId, operations, spec.headlines());
    List<String> longHeadlineAssets =
        appendTextAssetOperations(tempIds, customerId, operations, spec.longHeadlines());
    List<String> descriptionAssets =
        appendTextAssetOperations(tempIds, customerId, operations, spec.descriptions());
    List<String> businessNameAssets =
        appendTextAssetOperations(tempIds, customerId, operations, List.of(spec.businessName()));

    int assetGroupIndex = operations.size();
    String assetGroupResourceName = tempIds.tempResourceName(customerId, "assetGroups");
    ObjectNode assetGroup =
        operations.addObject().putObject("assetGroupOperation").putObject("create");
    assetGroup.put("resourceName", assetGroupResourceName);
    assetGroup.put("name", spec.name() + " — Asset Group");
    assetGroup.put("campaign", campaignResourceName);
    assetGroup.putArray("finalUrls").add(spec.finalUrl());
    // ENABLED, because the PAUSED campaign is the single gate on spend. An asset group left
    // paused inside a campaign somebody later activates would serve nothing and read as a
    // broken campaign.
    assetGroup.put("status", "ENABLED");

    int assetLinkCount = 0;
    assetLinkCount +=
        assetGroupAssetLinks(operations, assetGroupResourceName, headlineAssets, FIELD_HEADLINE);
    assetLinkCount +=
        assetGroupAssetLinks(
            operations, assetGroupResourceName, longHeadlineAssets, FIELD_LONG_HEADLINE);
    assetLinkCount +=
        assetGroupAssetLinks(
            operations, assetGroupResourceName, descriptionAssets, FIELD_DESCRIPTION);
    for (GoogleAssetLinkSpec link : assetGroupMediaLinks) {
      ObjectNode create =
          operations.addObject().putObject("assetGroupAssetOperation").putObject("create");
      create.put("assetGroup", assetGroupResourceName);
      create.put("asset", link.assetResourceName());
      create.put("fieldType", sanitizeFieldType(link.fieldType()));
      assetLinkCount++;
    }
    // The business name and the logos go on the CAMPAIGN: brand guidelines are on, and Google
    // refuses those field types on an asset group whose campaign has them.
    for (String assetResourceName : businessNameAssets) {
      campaignAssetLink(operations, campaignResourceName, assetResourceName, FIELD_BUSINESS_NAME);
      assetLinkCount++;
    }
    for (GoogleAssetLinkSpec link : campaignMediaLinks) {
      campaignAssetLink(
          operations, campaignResourceName, link.assetResourceName(), link.fieldType());
      assetLinkCount++;
    }

    for (String theme : spec.searchThemes()) {
      ObjectNode signal =
          operations.addObject().putObject("assetGroupSignalOperation").putObject("create");
      signal.put("assetGroup", assetGroupResourceName);
      signal.putObject("searchTheme").put("text", theme);
    }
    return new GoogleAdsPmaxPlan(operations, campaignIndex, assetGroupIndex, assetLinkCount);
  }

  // Appends one asset create per text and returns the temporary resource names in the same
  // order, so the caller can zip them back onto the field type they belong to.
  private static List<String> appendTextAssetOperations(
      GoogleAdsTempIds tempIds, String customerId, ArrayNode operations, List<String> texts) {
    List<String> resourceNames = new ArrayList<>();
    for (String text : texts) {
      String resourceName = tempIds.tempResourceName(customerId, "assets");
      ObjectNode create = operations.addObject().putObject("assetOperation").putObject("create");
      create.put("resourceName", resourceName);
      // Asset.type is output-only — the assetData oneof implies it, and sending a type is a
      // 400.
      create.putObject("textAsset").put("text", text);
      resourceNames.add(resourceName);
    }
    return resourceNames;
  }

  private static int assetGroupAssetLinks(
      ArrayNode operations,
      String assetGroupResourceName,
      List<String> assetResourceNames,
      String fieldType) {
    for (String assetResourceName : assetResourceNames) {
      ObjectNode create =
          operations.addObject().putObject("assetGroupAssetOperation").putObject("create");
      create.put("assetGroup", assetGroupResourceName);
      create.put("asset", assetResourceName);
      create.put("fieldType", sanitizeFieldType(fieldType));
    }
    return assetResourceNames.size();
  }

  private static void campaignAssetLink(
      ArrayNode operations,
      String campaignResourceName,
      String assetResourceName,
      String fieldType) {
    ObjectNode create =
        operations.addObject().putObject("campaignAssetOperation").putObject("create");
    create.put("campaign", campaignResourceName);
    create.put("asset", assetResourceName);
    create.put("fieldType", sanitizeFieldType(fieldType));
  }

  public String createAdGroup(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String name,
      String campaignResourceName) {
    return createAdGroup(
        accessToken, customerId, loginCustomerId, name, campaignResourceName, null, "ENABLED");
  }

  // Same, with an explicit default CPC bid and status. The bid is stored even under
  // an automated bidding strategy, where Google simply ignores it.
  public String createAdGroup(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String name,
      String campaignResourceName,
      Long cpcBidMicros,
      String status) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("name", name);
    create.put("status", status);
    create.put("type", "SEARCH_STANDARD");
    create.put("campaign", campaignResourceName);
    if (cpcBidMicros != null) {
      create.put("cpcBidMicros", cpcBidMicros);
    }
    log.debug(
        "Creating ad group '{}' in {} for customer {} (status {}, bid {} micros)",
        name,
        campaignResourceName,
        customerId,
        status,
        cpcBidMicros);
    return mutate(accessToken, customerId, loginCustomerId, "adGroups", operation);
  }

  // Renames an ad group and/or sets its default CPC bid; the update mask carries only
  // the fields the caller supplied, so an omitted field is left untouched.
  public void updateAdGroup(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupId,
      String name,
      Long cpcBidMicros) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put(
        "resourceName",
        "customers/" + sanitizeId(customerId) + "/adGroups/" + sanitizeId(adGroupId));
    List<String> mask = new ArrayList<>();
    if (name != null) {
      update.put("name", name);
      mask.add("name");
    }
    if (cpcBidMicros != null) {
      update.put("cpcBidMicros", cpcBidMicros);
      mask.add("cpc_bid_micros");
    }
    operation.put("updateMask", String.join(",", mask));
    log.debug("Updating ad group {} of customer {} with mask {}", adGroupId, customerId, mask);
    mutate(accessToken, customerId, loginCustomerId, "adGroups", operation);
  }

  // The plain-text form used when a whole campaign is built in one go: no pins, no display
  // paths, and the ad goes live with the campaign it belongs to, which is itself created
  // PAUSED. Delegates to the full form below.
  public String createResponsiveSearchAd(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupResourceName,
      String finalUrl,
      List<String> headlines,
      List<String> descriptions) {
    return createResponsiveSearchAd(
        accessToken,
        customerId,
        loginCustomerId,
        adGroupResourceName,
        finalUrl,
        headlines.stream().map(text -> new GoogleAdTextAssetDto(text, null)).toList(),
        descriptions.stream().map(text -> new GoogleAdTextAssetDto(text, null)).toList(),
        null,
        null,
        "ENABLED");
  }

  // Adds a responsive search ad to an ad group that already exists. Returns the
  // adGroupAds resource name, whose id is the composite {adGroupId}~{adId}.
  public String createResponsiveSearchAd(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupResourceName,
      String finalUrl,
      List<GoogleAdTextAssetDto> headlines,
      List<GoogleAdTextAssetDto> descriptions,
      String path1,
      String path2,
      String status) {
    ObjectNode operation =
        rsaCreateOperation(
            objectMapper,
            adGroupResourceName,
            finalUrl,
            headlines,
            descriptions,
            path1,
            path2,
            status);
    log.debug(
        "Creating {} responsive search ad in {} of customer {}: {} headlines, {} descriptions",
        status,
        adGroupResourceName,
        customerId,
        headlines.size(),
        descriptions.size());
    return mutate(accessToken, customerId, loginCustomerId, "adGroupAds", operation);
  }

  // A create carries no update mask, so unlike adCopyUpdateOperation an omitted display
  // path is simply absent rather than cleared. Pins travel through the same writeAssets
  // the update path uses, so an ad created here can be pinned exactly like one edited.
  static ObjectNode rsaCreateOperation(
      ObjectMapper objectMapper,
      String adGroupResourceName,
      String finalUrl,
      List<GoogleAdTextAssetDto> headlines,
      List<GoogleAdTextAssetDto> descriptions,
      String path1,
      String path2,
      String status) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("adGroup", adGroupResourceName);
    create.put("status", status);
    ObjectNode ad = create.putObject("ad");
    ad.putArray("finalUrls").add(finalUrl);
    ObjectNode rsa = ad.putObject("responsiveSearchAd");
    writeAssets(rsa.putArray("headlines"), headlines);
    writeAssets(rsa.putArray("descriptions"), descriptions);
    if (path1 != null && !path1.isBlank()) {
      rsa.put("path1", path1);
    }
    if (path2 != null && !path2.isBlank()) {
      rsa.put("path2", path2);
    }
    return operation;
  }

  // Rewrites the copy of an existing responsive search ad through AdService. The ad keeps
  // its id and its performance history; Google puts it back into policy review. Repeated
  // fields are replaced wholesale, so callers send complete lists.
  public void updateResponsiveSearchAd(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adId,
      List<GoogleAdTextAssetDto> headlines,
      List<GoogleAdTextAssetDto> descriptions,
      String finalUrl,
      String path1,
      String path2) {
    String resourceName = "customers/" + sanitizeId(customerId) + "/ads/" + sanitizeId(adId);
    ObjectNode operation =
        adCopyUpdateOperation(
            objectMapper, resourceName, headlines, descriptions, finalUrl, path1, path2);
    log.debug(
        "Updating ad {} of customer {} with mask {}",
        adId,
        customerId,
        operation.path("updateMask").asText());
    mutate(accessToken, customerId, loginCustomerId, "ads", operation);
  }

  // Only what the caller passed reaches the body and the mask; an argument left null is
  // absent from both, so the ad keeps that field. A path cleared to "" is masked, which
  // is how Google removes it.
  static ObjectNode adCopyUpdateOperation(
      ObjectMapper objectMapper,
      String adResourceName,
      List<GoogleAdTextAssetDto> headlines,
      List<GoogleAdTextAssetDto> descriptions,
      String finalUrl,
      String path1,
      String path2) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", adResourceName);
    List<String> mask = new ArrayList<>();
    if (finalUrl != null) {
      update.putArray("finalUrls").add(finalUrl);
      mask.add("final_urls");
    }
    if (headlines != null || descriptions != null || path1 != null || path2 != null) {
      ObjectNode rsa = update.putObject("responsiveSearchAd");
      if (headlines != null) {
        writeAssets(rsa.putArray("headlines"), headlines);
        mask.add("responsive_search_ad.headlines");
      }
      if (descriptions != null) {
        writeAssets(rsa.putArray("descriptions"), descriptions);
        mask.add("responsive_search_ad.descriptions");
      }
      if (path1 != null) {
        rsa.put("path1", path1);
        mask.add("responsive_search_ad.path1");
      }
      if (path2 != null) {
        rsa.put("path2", path2);
        mask.add("responsive_search_ad.path2");
      }
    }
    operation.put("updateMask", String.join(",", mask));
    return operation;
  }

  private static void writeAssets(ArrayNode arr, List<GoogleAdTextAssetDto> assets) {
    for (GoogleAdTextAssetDto asset : assets) {
      ObjectNode node = arr.addObject();
      node.put("text", asset.text());
      if (asset.pinnedField() != null) {
        node.put("pinnedField", asset.pinnedField());
      }
    }
  }

  // Adds enabled BROAD match keywords to the ad group; one mutate call with N operations.
  public void addKeywords(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupResourceName,
      List<String> keywords) {
    List<GoogleKeywordCreateSpec> specs = new ArrayList<>();
    for (String keyword : keywords) {
      specs.add(new GoogleKeywordCreateSpec(keyword, "BROAD", false, null, "ENABLED"));
    }
    createKeywords(accessToken, customerId, loginCustomerId, adGroupResourceName, specs);
  }

  // Adds keyword criteria with per-keyword match type, status, negativity, and bid.
  // Negative criteria carry no bid — Google rejects one. Returns the resource names
  // of what was created, in the order the specs were given.
  public List<String> createKeywords(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupResourceName,
      List<GoogleKeywordCreateSpec> keywords) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (GoogleKeywordCreateSpec keyword : keywords) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("adGroup", adGroupResourceName);
      create.put("status", keyword.status());
      create.put("negative", keyword.negative());
      if (!keyword.negative() && keyword.cpcBidMicros() != null) {
        create.put("cpcBidMicros", keyword.cpcBidMicros());
      }
      ObjectNode info = create.putObject("keyword");
      info.put("text", keyword.text());
      info.put("matchType", keyword.matchType());
    }
    JsonNode response =
        mutateAll(accessToken, customerId, loginCustomerId, "adGroupCriteria", operations);
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug(
        "Created {} keyword criteria in {} for customer {}",
        resourceNames.size(),
        adGroupResourceName,
        customerId);
    return resourceNames;
  }

  // Pauses/enables a single keyword criterion and/or sets its CPC bid. Criterion
  // resource ids are composite: {adGroupId}~{criterionId}.
  public void updateKeyword(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupId,
      String criterionId,
      String status,
      Long cpcBidMicros) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", criterionResourceName(customerId, adGroupId, criterionId));
    List<String> mask = new ArrayList<>();
    if (status != null) {
      update.put("status", status);
      mask.add("status");
    }
    if (cpcBidMicros != null) {
      update.put("cpcBidMicros", cpcBidMicros);
      mask.add("cpc_bid_micros");
    }
    operation.put("updateMask", String.join(",", mask));
    log.debug(
        "Updating keyword {}~{} of customer {} with mask {}",
        adGroupId,
        criterionId,
        customerId,
        mask);
    mutate(accessToken, customerId, loginCustomerId, "adGroupCriteria", operation);
  }

  // Attaches location criteria to a campaign: one campaignCriteria:mutate with N
  // creates. `negative` true excludes the location instead of targeting it. Returns the
  // created resource names in the order given, so a caller mid-build can roll them back.
  public List<String> addLocationCriteria(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignResourceName,
      List<String> geoTargetIds,
      boolean negative) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (String geoTargetId : geoTargetIds) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("campaign", campaignResourceName);
      // negative is immutable and defaults to false, so it is only ever written when a
      // location is being excluded — sending the default back is what IMMUTABLE_FIELD
      // complains about.
      if (negative) {
        create.put("negative", true);
      }
      create
          .putObject("location")
          .put("geoTargetConstant", "geoTargetConstants/" + sanitizeId(geoTargetId));
    }
    JsonNode response =
        mutateAll(accessToken, customerId, loginCustomerId, "campaignCriteria", operations);
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug(
        "Created {} {} location criteria on {} for customer {}",
        resourceNames.size(),
        negative ? "excluded" : "targeted",
        campaignResourceName,
        customerId);
    return resourceNames;
  }

  // Attaches language criteria to a campaign. Language criteria are never negative —
  // Google rejects one — so the campaign either targets a language or does not.
  public List<String> addLanguageCriteria(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignResourceName,
      List<String> languageIds) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (String languageId : languageIds) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("campaign", campaignResourceName);
      create
          .putObject("language")
          .put("languageConstant", "languageConstants/" + sanitizeId(languageId));
    }
    JsonNode response =
        mutateAll(accessToken, customerId, loginCustomerId, "campaignCriteria", operations);
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug(
        "Created {} language criteria on {} for customer {}",
        resourceNames.size(),
        campaignResourceName,
        customerId);
    return resourceNames;
  }

  // Removes campaign criteria for good. Criterion resource ids are composite:
  // {campaignId}~{criterionId}, the same shape keyword criteria use.
  public void removeCampaignCriteria(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      List<String> criterionIds) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (String criterionId : criterionIds) {
      operations
          .addObject()
          .put(
              "remove",
              "customers/"
                  + sanitizeId(customerId)
                  + "/campaignCriteria/"
                  + sanitizeId(campaignId)
                  + "~"
                  + sanitizeId(criterionId));
    }
    log.debug(
        "Removing {} campaign criteria from campaign {} of customer {}",
        criterionIds.size(),
        campaignId,
        customerId);
    mutateAll(accessToken, customerId, loginCustomerId, "campaignCriteria", operations);
  }

  // PRESENCE means "people in the targeted area"; PRESENCE_OR_INTEREST, Google's
  // default, also reaches people elsewhere who show interest in it.
  public void updatePositiveGeoTargetType(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      String positiveGeoTargetType) {
    String resourceName =
        "customers/" + sanitizeId(customerId) + "/campaigns/" + sanitizeId(campaignId);
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", resourceName);
    update.putObject("geoTargetTypeSetting").put("positiveGeoTargetType", positiveGeoTargetType);
    operation.put("updateMask", "geo_target_type_setting.positive_geo_target_type");
    log.debug(
        "Setting positive geo target type of campaign {} to {}", campaignId, positiveGeoTargetType);
    mutate(accessToken, customerId, loginCustomerId, "campaigns", operation);
  }

  // Removes keyword criteria for good; one mutate call with N remove operations.
  public void removeKeywords(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String adGroupId,
      List<String> criterionIds) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (String criterionId : criterionIds) {
      operations
          .addObject()
          .put("remove", criterionResourceName(customerId, adGroupId, criterionId));
    }
    log.debug(
        "Removing {} keyword criteria from ad group {} of customer {}",
        criterionIds.size(),
        adGroupId,
        customerId);
    mutateAll(accessToken, customerId, loginCustomerId, "adGroupCriteria", operations);
  }

  // Trailing id of a resource name ("geoTargetConstants/1012852" -> "1012852").
  private static String lastSegment(String resourceName) {
    if (resourceName == null || resourceName.isBlank()) {
      return null;
    }
    return resourceName.substring(resourceName.lastIndexOf('/') + 1);
  }

  private String criterionResourceName(String customerId, String adGroupId, String criterionId) {
    return "customers/"
        + sanitizeId(customerId)
        + "/adGroupCriteria/"
        + sanitizeId(adGroupId)
        + "~"
        + sanitizeId(criterionId);
  }

  // Rollback helper: removes any created resource by its resource name — the
  // mutate service is the resource name's third segment (customers/{id}/{service}/{oid}).
  public void removeResource(
      String accessToken, String customerId, String loginCustomerId, String resourceName) {
    String service = resourceName.split("/")[2];
    ObjectNode operation = objectMapper.createObjectNode();
    operation.put("remove", resourceName);
    mutate(accessToken, customerId, loginCustomerId, service, operation);
  }

  // Offline conversion import: tells Google that a click it already knows about later
  // turned into a signup, a connected ad account or a payment. The events happen on the
  // server hours or days after the click, so they cannot be a browser tag.
  //
  // partialFailure is mandatory here — Google rejects the request outright without it —
  // and it also means a bad row comes back as a per-row error rather than failing the
  // whole upload, so the caller must read partialFailureError instead of trusting a 200.
  public JsonNode uploadClickConversions(
      String accessToken,
      String customerId,
      String loginCustomerId,
      List<ClickConversion> conversions) {
    ArrayNode rows = objectMapper.createArrayNode();
    for (ClickConversion conversion : conversions) {
      ObjectNode row = objectMapper.createObjectNode();
      row.put("conversionAction", conversion.conversionAction());
      row.put(conversion.clickIdField(), conversion.clickIdValue());
      row.put("conversionDateTime", conversion.conversionDateTime());
      if (conversion.value() != null) {
        row.put("conversionValue", conversion.value());
        row.put("currencyCode", conversion.currency());
      }
      if (conversion.orderId() != null && !conversion.orderId().isBlank()) {
        row.put("orderId", conversion.orderId());
      }
      rows.add(row);
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.set("conversions", rows);
    body.put("partialFailure", true);
    return post(
        apiBase() + "/customers/" + sanitizeId(customerId) + ":uploadClickConversions",
        accessToken,
        loginCustomerId,
        body,
        "Google Ads uploadClickConversions",
        GoogleAdsRequestContext.of(customerId, loginCustomerId, body));
  }

  // One row of an offline conversion upload. `clickIdField` is gclid, gbraid or wbraid —
  // Google accepts exactly one of them per conversion, under its own field name.
  public record ClickConversion(
      String conversionAction,
      String clickIdField,
      String clickIdValue,
      String conversionDateTime,
      Double value,
      String currency,
      String orderId) {}

  // --- HTTP plumbing ----------------------------------------------------------

  // Runs one GAQL query, flattening searchStream's batch array into result rows.
  public List<JsonNode> searchStream(
      String accessToken, String customerId, String loginCustomerId, String gaql) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("query", gaql);
    JsonNode response =
        post(
            apiBase() + "/customers/" + sanitizeId(customerId) + "/googleAds:searchStream",
            accessToken,
            loginCustomerId,
            body,
            "Google Ads searchStream",
            GoogleAdsRequestContext.of(customerId, loginCustomerId, body));
    List<JsonNode> rows = new ArrayList<>();
    // searchStream returns an array of batches, each with a results array.
    for (JsonNode batch :
        response.isArray() ? response : objectMapper.createArrayNode().add(response)) {
      for (JsonNode row : batch.path("results")) {
        rows.add(row);
      }
    }
    return rows;
  }

  // Everything a campaign is told not to serve on. Kept separate from targetingCriteriaGaql
  // because that query runs for every campaign in google_list_campaigns, and folding keyword
  // rows into it would multiply that result for readers that never wanted them.
  //
  // A null campaignId reads the whole account, which is what answers "is this brand list
  // shared with other campaigns" — the same question budgetExplicitlyShared answers for
  // budgets.
  static String campaignNegativesGaql(String campaignId) {
    return "SELECT campaign_criterion.criterion_id, campaign_criterion.type,"
        + " campaign_criterion.negative, campaign_criterion.status,"
        + " campaign_criterion.display_name,"
        + " campaign_criterion.keyword.text, campaign_criterion.keyword.match_type,"
        + " campaign_criterion.brand_list.shared_set, campaign.id, campaign.name"
        + " FROM campaign_criterion"
        + " WHERE campaign_criterion.type IN ('KEYWORD', 'BRAND_LIST')"
        + " AND campaign_criterion.negative = true"
        + " AND campaign_criterion.status != 'REMOVED'"
        + (campaignId == null ? "" : " AND campaign.id = " + sanitizeId(campaignId));
  }

  public GoogleCampaignNegativesDto listCampaignNegatives(
      String accessToken, String customerId, String loginCustomerId, String campaignId) {
    List<GoogleCampaignNegativeKeywordDto> keywords = new ArrayList<>();
    List<GoogleBrandExclusionDto> brandExclusions = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, campaignNegativesGaql(campaignId))) {
      JsonNode criterion = row.path("campaignCriterion");
      String rowCampaignId = row.path("campaign").path("id").asText(null);
      if ("KEYWORD".equals(criterion.path("type").asText(null))) {
        keywords.add(
            new GoogleCampaignNegativeKeywordDto(
                rowCampaignId,
                criterion.path("criterionId").asText(null),
                criterion.path("keyword").path("text").asText(null),
                criterion.path("keyword").path("matchType").asText(null),
                criterion.path("status").asText(null)));
        continue;
      }
      String sharedSet = criterion.path("brandList").path("sharedSet").asText(null);
      brandExclusions.add(
          new GoogleBrandExclusionDto(
              rowCampaignId,
              criterion.path("criterionId").asText(null),
              lastSegment(sharedSet),
              criterion.path("displayName").asText(null),
              List.of(),
              List.of()));
    }
    log.debug(
        "Campaign {} of customer {} has {} negative keywords and {} brand exclusions",
        campaignId,
        customerId,
        keywords.size(),
        brandExclusions.size());
    return new GoogleCampaignNegativesDto(keywords, brandExclusions);
  }

  // The brands inside one BRANDS shared set. Filtered by shared_set.id rather than by its
  // resource name so the value goes through sanitizeId; a resource name interpolated into
  // GAQL would need a sanitizer of its own.
  public List<GoogleBrandDto> listSharedSetBrands(
      String accessToken, String customerId, String loginCustomerId, String sharedSetId) {
    String gaql =
        "SELECT shared_criterion.criterion_id, shared_criterion.type,"
            + " shared_criterion.brand.entity_id, shared_criterion.brand.display_name,"
            + " shared_criterion.brand.primary_url, shared_set.id, shared_set.name"
            + " FROM shared_criterion WHERE shared_set.id = "
            + sanitizeId(sharedSetId)
            + " AND shared_criterion.status != 'REMOVED'";
    List<GoogleBrandDto> brands = new ArrayList<>();
    for (JsonNode row : searchStream(accessToken, customerId, loginCustomerId, gaql)) {
      JsonNode criterion = row.path("sharedCriterion");
      brands.add(
          new GoogleBrandDto(
              criterion.path("criterionId").asText(null),
              criterion.path("brand").path("entityId").asText(null),
              criterion.path("brand").path("displayName").asText(null),
              criterion.path("brand").path("primaryUrl").asText(null)));
    }
    log.debug(
        "Shared set {} of customer {} holds {} brands", sharedSetId, customerId, brands.size());
    return brands;
  }

  // Brands Google recognizes for a prefix. Entity ids are Knowledge Graph machine ids, not
  // numbers, so there is no way to guess one — this call is the only way to turn a brand name
  // the user typed into something a brand criterion accepts.
  public List<GoogleBrandSuggestionDto> suggestBrands(
      String accessToken, String customerId, String loginCustomerId, String brandPrefix) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("brandPrefix", brandPrefix);
    JsonNode response =
        post(
            apiBase() + "/customers/" + sanitizeId(customerId) + ":suggestBrands",
            accessToken,
            loginCustomerId,
            body,
            "Google Ads customers:suggestBrands",
            GoogleAdsRequestContext.of(customerId, loginCustomerId, body));
    List<GoogleBrandSuggestionDto> brands = new ArrayList<>();
    for (JsonNode brand : response.path("brands")) {
      List<String> urls = new ArrayList<>();
      for (JsonNode url : brand.path("urls")) {
        urls.add(url.asText());
      }
      brands.add(
          new GoogleBrandSuggestionDto(
              brand.path("id").asText(null),
              brand.path("name").asText(null),
              urls,
              brand.path("state").asText(null)));
    }
    log.debug(
        "Brand prefix \"{}\" matched {} brands for customer {}",
        brandPrefix,
        brands.size(),
        customerId);
    return brands;
  }

  // Campaign-level negative keyword criteria: one campaignCriteria:mutate with N creates.
  //
  // Unlike addLocationCriteria, `negative` is written explicitly and always true — a keyword
  // criterion on a campaign is only ever an exclusion, and Performance Max rejects a positive
  // one outright. CampaignCriterion.negative is also immutable, so it has to be right on
  // create. No status is sent: it is not settable on a negative criterion.
  public List<String> addCampaignNegativeKeywords(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignResourceName,
      List<GoogleNegativeKeywordSpec> keywords) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (GoogleNegativeKeywordSpec keyword : keywords) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("campaign", campaignResourceName);
      create.put("negative", true);
      ObjectNode info = create.putObject("keyword");
      info.put("text", keyword.text());
      info.put("matchType", keyword.matchType());
    }
    JsonNode response =
        mutateAll(accessToken, customerId, loginCustomerId, "campaignCriteria", operations);
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug(
        "Added {} negative keywords to {} for customer {}",
        resourceNames.size(),
        campaignResourceName,
        customerId);
    return resourceNames;
  }

  // A BRANDS shared set to hold brand exclusions. Named after the campaign it was built for,
  // because shared set names are unique per account and a server-created object has to be
  // identifiable later.
  public String createBrandSharedSet(
      String accessToken, String customerId, String loginCustomerId, String name) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("name", name);
    create.put("type", "BRANDS");
    String resourceName = mutate(accessToken, customerId, loginCustomerId, "sharedSets", operation);
    log.debug("Created brand shared set {} for customer {}", resourceName, customerId);
    return resourceName;
  }

  // BrandInfo.entity_id is the only settable field; display_name and primary_url are
  // output-only and Google fills them in from the entity id.
  public List<String> addSharedSetBrands(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String sharedSetResourceName,
      List<String> brandEntityIds) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (String entityId : brandEntityIds) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("sharedSet", sharedSetResourceName);
      create.putObject("brand").put("entityId", entityId);
    }
    JsonNode response =
        mutateAll(accessToken, customerId, loginCustomerId, "sharedCriteria", operations);
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug(
        "Added {} brands to {} for customer {}",
        resourceNames.size(),
        sharedSetResourceName,
        customerId);
    return resourceNames;
  }

  // Attaches a brand list to a campaign as a NEGATIVE criterion. Note that CampaignSharedSet
  // cannot carry BRANDS — it only accepts negative keyword and placement sets — so this is
  // the only path, which is easy to get wrong from the shared-sets guide alone.
  public String addBrandListCriterion(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignResourceName,
      String sharedSetResourceName) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("campaign", campaignResourceName);
    create.put("negative", true);
    create.putObject("brandList").put("sharedSet", sharedSetResourceName);
    String resourceName =
        mutate(accessToken, customerId, loginCustomerId, "campaignCriteria", operation);
    log.debug(
        "Attached brand list {} to {} for customer {}",
        sharedSetResourceName,
        campaignResourceName,
        customerId);
    return resourceName;
  }

  // The plain leaf fields of a campaign: its name and the dates it runs between. Only what
  // the caller passed reaches the body and the mask, and a masked path with no value in the
  // body is cleared — which is how an end date is removed rather than moved.
  public void updateCampaignSettings(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      GoogleCampaignSettingsUpdate spec) {
    mutate(
        accessToken,
        customerId,
        loginCustomerId,
        "campaigns",
        campaignSettingsUpdateOperation(
            objectMapper,
            "customers/" + sanitizeId(customerId) + "/campaigns/" + sanitizeId(campaignId),
            spec));
    log.debug(
        "Updated settings of campaign {} for customer {}: name={} startDate={} endDate={}"
            + " clearEndDate={} (written as start_date_time/end_date_time)",
        campaignId,
        customerId,
        spec.name(),
        spec.startDate(),
        spec.endDate(),
        spec.clearEndDate());
  }

  static ObjectNode campaignSettingsUpdateOperation(
      ObjectMapper objectMapper, String campaignResourceName, GoogleCampaignSettingsUpdate spec) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", campaignResourceName);
    List<String> mask = new ArrayList<>();
    if (spec.name() != null) {
      update.put("name", spec.name());
      mask.add("name");
    }
    // v24 renamed start_date/end_date to start_date_time/end_date_time and changed their
    // type: they are "yyyy-MM-dd HH:mm:ss" in the serving customer's timezone, not bare
    // dates. The spec carries a plain date because that is what a caller schedules in, and
    // the time component is appended here — Google's wire format belongs in the one class
    // that owns it. Google's own reference prescribes these exact times for a campaign
    // scheduled by day: 00:00:00 to start, 23:59:59 to end.
    if (spec.startDate() != null) {
      update.put("startDateTime", sanitizeDate(spec.startDate()) + START_OF_DAY);
      mask.add("start_date_time");
    }
    if (spec.clearEndDate()) {
      // Masked with no value in the body, which is how Google clears a field — and a
      // cleared end_date_time is how v24 expresses "runs indefinitely".
      mask.add("end_date_time");
    } else if (spec.endDate() != null) {
      update.put("endDateTime", sanitizeDate(spec.endDate()) + END_OF_DAY);
      mask.add("end_date_time");
    }
    operation.put("updateMask", String.join(",", mask));
    return operation;
  }

  // The asset group's own fields: name, status, final URLs and display paths. Every path in
  // the mask is already a leaf — final_urls is a repeated scalar, path1 and path2 are scalars
  // directly on AssetGroup — so unlike campaignBiddingUpdateOperation none of them can trip
  // fieldMaskError.FIELD_HAS_SUBFIELDS. A masked path whose value is absent from the body is
  // cleared, which is how a display path is removed.
  //
  // `campaign` is deliberately absent: an asset group cannot be moved between campaigns, so
  // sending it is a rejected request rather than a no-op.
  public void updateAssetGroup(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String assetGroupId,
      String name,
      String status,
      List<String> finalUrls,
      String path1,
      String path2) {
    mutate(
        accessToken,
        customerId,
        loginCustomerId,
        "assetGroups",
        assetGroupUpdateOperation(
            objectMapper,
            "customers/" + sanitizeId(customerId) + "/assetGroups/" + sanitizeId(assetGroupId),
            name,
            status,
            finalUrls,
            path1,
            path2));
    log.debug("Updated asset group {} for customer {}", assetGroupId, customerId);
  }

  static ObjectNode assetGroupUpdateOperation(
      ObjectMapper objectMapper,
      String assetGroupResourceName,
      String name,
      String status,
      List<String> finalUrls,
      String path1,
      String path2) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", assetGroupResourceName);
    List<String> mask = new ArrayList<>();
    if (name != null) {
      update.put("name", name);
      mask.add("name");
    }
    if (status != null) {
      update.put("status", status);
      mask.add("status");
    }
    if (finalUrls != null) {
      ArrayNode urls = update.putArray("finalUrls");
      finalUrls.forEach(urls::add);
      mask.add("final_urls");
    }
    // A blank path is how a display path is removed: the path is masked and the value left
    // out of the body, so Google clears it.
    if (path1 != null) {
      if (!path1.isBlank()) {
        update.put("path1", path1);
      }
      mask.add("path1");
    }
    if (path2 != null) {
      if (!path2.isBlank()) {
        update.put("path2", path2);
      }
      mask.add("path2");
    }
    operation.put("updateMask", String.join(",", mask));
    return operation;
  }

  // Text assets, one assets:mutate with N creates. Google's AssetService deduplicates a
  // text asset by its content, so a create for text the account already holds comes back
  // with the existing asset's resource name — which is exactly the one to link. Resource
  // names come back in the order the texts went in, which is what lets the caller zip them
  // back onto the field types they belong to.
  public List<String> createTextAssets(
      String accessToken, String customerId, String loginCustomerId, List<String> texts) {
    JsonNode response =
        mutateAll(
            accessToken,
            customerId,
            loginCustomerId,
            "assets",
            textAssetOperations(objectMapper, texts));
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug("Created {} text assets for customer {}", resourceNames.size(), customerId);
    return resourceNames;
  }

  static ArrayNode textAssetOperations(ObjectMapper objectMapper, List<String> texts) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (String text : texts) {
      // Asset.type is output-only — the assetData oneof implies it, and sending a type is
      // a 400.
      operations.addObject().putObject("create").putObject("textAsset").put("text", text);
    }
    return operations;
  }

  // The assets held on a campaign rather than on an asset group. Under brand guidelines this
  // is where a Performance Max campaign's business name and logos live.
  static String campaignAssetsGaql(String campaignId) {
    return "SELECT campaign_asset.field_type, campaign_asset.status, campaign.id,"
        + " asset.id, asset.resource_name, asset.text_asset.text,"
        + " asset.image_asset.full_size.width_pixels,"
        + " asset.image_asset.full_size.height_pixels"
        + " FROM campaign_asset WHERE campaign.id = "
        + sanitizeId(campaignId)
        + " AND campaign_asset.status != 'REMOVED'"
        + " ORDER BY campaign_asset.field_type";
  }

  public List<GoogleCampaignAssetDto> listCampaignAssets(
      String accessToken, String customerId, String loginCustomerId, String campaignId) {
    List<GoogleCampaignAssetDto> assets = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, campaignAssetsGaql(campaignId))) {
      JsonNode link = row.path("campaignAsset");
      JsonNode asset = row.path("asset");
      JsonNode fullSize = asset.path("imageAsset").path("fullSize");
      assets.add(
          new GoogleCampaignAssetDto(
              row.path("campaign").path("id").asText(null),
              asset.path("id").asText(null),
              asset.path("resourceName").asText(null),
              link.path("fieldType").asText(null),
              link.path("status").asText(null),
              asset.path("textAsset").path("text").asText(null),
              fullSize.hasNonNull("widthPixels") ? fullSize.get("widthPixels").asInt() : null,
              fullSize.hasNonNull("heightPixels") ? fullSize.get("heightPixels").asInt() : null));
    }
    log.debug("Campaign {} of customer {} holds {} assets", campaignId, customerId, assets.size());
    return assets;
  }

  // Brand assets are swapped the same way asset-group assets are: CampaignAsset cannot be
  // updated either, so a replacement is a link create plus a link remove, and both go in one
  // atomic mutate so the campaign is never momentarily without the business name or logo that
  // brand guidelines require it to have.
  public void mutateCampaignAssetLinks(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String campaignId,
      List<GoogleAssetLinkSpec> toLink,
      List<GoogleAssetLinkSpec> toUnlink) {
    mutateAll(
        accessToken,
        customerId,
        loginCustomerId,
        "campaignAssets",
        campaignAssetLinkOperations(objectMapper, customerId, campaignId, toLink, toUnlink));
    log.debug(
        "Campaign {} linked {} and unlinked {} assets for customer {}",
        campaignId,
        toLink.size(),
        toUnlink.size(),
        customerId);
  }

  static ArrayNode campaignAssetLinkOperations(
      ObjectMapper objectMapper,
      String customerId,
      String campaignId,
      List<GoogleAssetLinkSpec> toLink,
      List<GoogleAssetLinkSpec> toUnlink) {
    String campaignResourceName =
        "customers/" + sanitizeId(customerId) + "/campaigns/" + sanitizeId(campaignId);
    ArrayNode operations = objectMapper.createArrayNode();
    for (GoogleAssetLinkSpec spec : toLink) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("campaign", campaignResourceName);
      create.put("asset", spec.assetResourceName());
      create.put("fieldType", sanitizeFieldType(spec.fieldType()));
    }
    for (GoogleAssetLinkSpec spec : toUnlink) {
      // Same composite shape as an AssetGroupAsset: the resource name is the ids it joins.
      operations
          .addObject()
          .put(
              "remove",
              "customers/"
                  + sanitizeId(customerId)
                  + "/campaignAssets/"
                  + sanitizeId(campaignId)
                  + "~"
                  + sanitizeId(spec.assetId())
                  + "~"
                  + sanitizeFieldType(spec.fieldType()));
    }
    return operations;
  }

  // ---------------------------------------------------------------------------------------
  // Sitelinks.
  //
  // A sitelink is two things Google keeps apart: an Asset holding the text and the landing
  // page, and a link row saying where that asset serves. The same asset can be linked at
  // account, campaign and ad group level at once, so every read below is per level and every
  // write names the level it acts on.
  // ---------------------------------------------------------------------------------------

  public static final String SITELINK_FIELD_TYPE = "SITELINK";

  // The write path reads the current links to count them and to find what to unlink, and a
  // truncated read there would let a count check pass and then orphan assets. Google's ceiling
  // is 20 sitelinks per level, so 500 is far above anything real — same reasoning, and the
  // same number, as MAX_ASSET_LINK_ROWS.
  public static final int MAX_SITELINK_ROWS = 500;

  // Attributes only. Metrics are a separate query on purpose: they are only correct when
  // segmented by interaction target, and that segment doubles every row.
  static String sitelinksGaql(GoogleSitelinkLevel level, GoogleSitelinkQuery query) {
    String link = level.resource();
    StringBuilder gaql = new StringBuilder("SELECT ");
    gaql.append(link).append(".field_type, ").append(link).append(".status, ");
    gaql.append(link).append(".primary_status, ");
    gaql.append(sitelinkOwnerFields(level));
    gaql.append("asset.id, asset.resource_name, asset.name,")
        .append(" asset.sitelink_asset.link_text, asset.sitelink_asset.description1,")
        .append(" asset.sitelink_asset.description2, asset.sitelink_asset.start_date,")
        .append(" asset.sitelink_asset.end_date, asset.final_urls, asset.final_mobile_urls");
    gaql.append(" FROM ").append(link);
    gaql.append(" WHERE ").append(link).append(".field_type = '").append(SITELINK_FIELD_TYPE);
    gaql.append("' AND ").append(link).append(".status != 'REMOVED'");
    appendSitelinkOwnerFilter(gaql, level, query);
    return gaql.append(" ORDER BY ")
        .append(sitelinkOrderField(level))
        .append(" LIMIT ")
        .append(clampLimit(query.limit()))
        .toString();
  }

  // Per-sitelink performance. segments.asset_interaction_target.interaction_on_this_asset is
  // what separates a click on the sitelink from a click elsewhere in the same ad while it was
  // showing; without it Google's clicks column is the ad's, and reporting that as the
  // sitelink's is simply the wrong number.
  static String sitelinkMetricsGaql(GoogleSitelinkLevel level, GoogleSitelinkQuery query) {
    String link = level.resource();
    StringBuilder gaql = new StringBuilder("SELECT ");
    gaql.append(sitelinkOwnerFields(level));
    gaql.append("asset.id,")
        .append(" segments.asset_interaction_target.interaction_on_this_asset,")
        .append(" metrics.clicks, metrics.impressions, metrics.cost_micros,")
        .append(" metrics.conversions");
    gaql.append(" FROM ").append(link);
    gaql.append(" WHERE ").append(link).append(".field_type = '").append(SITELINK_FIELD_TYPE);
    gaql.append("' AND ").append(link).append(".status != 'REMOVED'");
    appendSitelinkOwnerFilter(gaql, level, query);
    gaql.append(" AND ");
    appendDateFilter(gaql, query.datePreset(), query.since(), query.until());
    return gaql.append(" LIMIT ").append(MAX_SITELINK_ROWS).toString();
  }

  // Everywhere one sitelink asset is linked at a given level. The edit tool asks this before
  // it mutates, because editing the asset changes the sitelink in every campaign holding it.
  static String sitelinkLinksGaql(GoogleSitelinkLevel level, String assetId) {
    String link = level.resource();
    StringBuilder gaql = new StringBuilder("SELECT ");
    gaql.append(link).append(".field_type, ").append(link).append(".status, ");
    gaql.append(sitelinkOwnerFields(level));
    gaql.append("asset.id, asset.resource_name");
    gaql.append(" FROM ").append(link);
    gaql.append(" WHERE ").append(link).append(".field_type = '").append(SITELINK_FIELD_TYPE);
    gaql.append("' AND ").append(link).append(".status != 'REMOVED' AND asset.id = ");
    gaql.append(sanitizeId(assetId));
    return gaql.append(" LIMIT ").append(MAX_SITELINK_ROWS).toString();
  }

  // A CustomerAsset row names no campaign or ad group — the link belongs to the account, so
  // there is nothing narrower to select. Campaign fields are deliberately absent from the ad
  // group query too: ad_group_asset attributes ad_group and asset, and selecting a campaign
  // column there is a 400 rather than a join.
  private static String sitelinkOwnerFields(GoogleSitelinkLevel level) {
    return switch (level) {
      case ACCOUNT -> "customer.id, ";
      case CAMPAIGN -> "campaign.id, campaign.name, ";
      case AD_GROUP -> "ad_group.id, ad_group.name, ";
    };
  }

  private static String sitelinkOrderField(GoogleSitelinkLevel level) {
    return switch (level) {
      case ACCOUNT -> "asset.id";
      case CAMPAIGN -> "campaign.id";
      case AD_GROUP -> "ad_group.id";
    };
  }

  private static void appendSitelinkOwnerFilter(
      StringBuilder gaql, GoogleSitelinkLevel level, GoogleSitelinkQuery query) {
    if (level == GoogleSitelinkLevel.CAMPAIGN && query.campaignId() != null) {
      gaql.append(" AND campaign.id = ").append(sanitizeId(query.campaignId()));
    }
    if (level == GoogleSitelinkLevel.AD_GROUP && query.adGroupId() != null) {
      gaql.append(" AND ad_group.id = ").append(sanitizeId(query.adGroupId()));
    }
  }

  public List<GoogleSitelinkDto> listSitelinks(
      String accessToken, String customerId, String loginCustomerId, GoogleSitelinkQuery query) {
    List<GoogleSitelinkDto> sitelinks = new ArrayList<>();
    for (GoogleSitelinkLevel level : query.levels()) {
      Map<String, GoogleSitelinkMetricsDto> metrics =
          query.includeMetrics()
              ? sitelinkMetrics(accessToken, customerId, loginCustomerId, level, query)
              : Map.of();
      for (JsonNode row :
          searchStream(accessToken, customerId, loginCustomerId, sitelinksGaql(level, query))) {
        sitelinks.add(toSitelink(level, row, metrics));
      }
    }
    log.debug(
        "Customer {} has {} sitelink links across levels {}",
        customerId,
        sitelinks.size(),
        query.levels());
    return sitelinks;
  }

  // The links of one asset, used by the edit path rather than by the report, so it is not
  // bounded by clampLimit and carries no metrics.
  public List<GoogleSitelinkDto> listSitelinkLinks(
      String accessToken,
      String customerId,
      String loginCustomerId,
      GoogleSitelinkLevel level,
      String assetId) {
    List<GoogleSitelinkDto> links = new ArrayList<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, sitelinkLinksGaql(level, assetId))) {
      links.add(toSitelink(level, row, Map.of()));
    }
    log.debug("Sitelink asset {} has {} {} links", assetId, links.size(), level);
    return links;
  }

  private Map<String, GoogleSitelinkMetricsDto> sitelinkMetrics(
      String accessToken,
      String customerId,
      String loginCustomerId,
      GoogleSitelinkLevel level,
      GoogleSitelinkQuery query) {
    Map<String, GoogleSitelinkMetricsDto> byKey = new LinkedHashMap<>();
    for (JsonNode row :
        searchStream(accessToken, customerId, loginCustomerId, sitelinkMetricsGaql(level, query))) {
      JsonNode metrics = row.path("metrics");
      boolean onThisAsset =
          row.path("segments")
              .path("assetInteractionTarget")
              .path("interactionOnThisAsset")
              .asBoolean(false);
      long clicks = metrics.path("clicks").asLong(0);
      // Only the clicks that actually hit the sitelink carry its cost and its conversions.
      // The other row is the rest of the ad, and crediting that to the sitelink is exactly
      // the inflated number this split exists to avoid.
      GoogleSitelinkMetricsDto measured =
          new GoogleSitelinkMetricsDto(
              onThisAsset ? clicks : 0,
              onThisAsset ? 0 : clicks,
              metrics.path("impressions").asLong(0),
              onThisAsset ? microsToCents(metrics.path("costMicros").asLong(0)) : 0,
              onThisAsset ? metrics.path("conversions").asDouble(0) : 0);
      String key = sitelinkMetricsKey(level, row);
      byKey.merge(key, measured, GoogleSitelinkMetricsDto::merge);
    }
    log.debug("Customer {} reported {} measured {} sitelinks", customerId, byKey.size(), level);
    return byKey;
  }

  private static String sitelinkMetricsKey(GoogleSitelinkLevel level, JsonNode row) {
    return sitelinkOwnerId(level, row) + "|" + row.path("asset").path("id").asText(null);
  }

  private static String sitelinkOwnerId(GoogleSitelinkLevel level, JsonNode row) {
    return switch (level) {
      case ACCOUNT -> null;
      case CAMPAIGN -> row.path("campaign").path("id").asText(null);
      case AD_GROUP -> row.path("adGroup").path("id").asText(null);
    };
  }

  private static GoogleSitelinkDto toSitelink(
      GoogleSitelinkLevel level, JsonNode row, Map<String, GoogleSitelinkMetricsDto> metrics) {
    JsonNode link = row.path(level.rowKey());
    JsonNode asset = row.path("asset");
    JsonNode sitelink = asset.path("sitelinkAsset");
    String ownerId = sitelinkOwnerId(level, row);
    String assetId = asset.path("id").asText(null);
    GoogleSitelinkMetricsDto measured = metrics.get(ownerId + "|" + assetId);
    return new GoogleSitelinkDto(
        level,
        ownerId,
        row.path(level.ownerRowKey()).path("name").asText(null),
        level == GoogleSitelinkLevel.CAMPAIGN ? ownerId : null,
        level == GoogleSitelinkLevel.CAMPAIGN
            ? row.path("campaign").path("name").asText(null)
            : null,
        assetId,
        asset.path("resourceName").asText(null),
        sitelink.path("linkText").asText(null),
        sitelink.path("description1").asText(null),
        sitelink.path("description2").asText(null),
        textList(asset.path("finalUrls")),
        textList(asset.path("finalMobileUrls")),
        link.path("status").asText(null),
        link.path("primaryStatus").asText(null),
        sitelink.path("startDate").asText(null),
        sitelink.path("endDate").asText(null),
        measured == null ? null : measured.clicksOnSitelink(),
        measured == null ? null : measured.clicksOnAdWithSitelink(),
        measured == null ? null : measured.impressions(),
        measured == null ? null : measured.costCents(),
        measured == null ? null : measured.conversions());
  }

  private static List<String> textList(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(value.asText());
    }
    return values;
  }

  // Sitelink assets, one assets:mutate with N creates.
  //
  // Unlike a text or image asset, Google does NOT deduplicate a sitelink by its content: the
  // same link text and URL sent twice becomes two assets, and an Asset can never be deleted
  // through the API. That is why the caller counts before it creates and why the create tool
  // is not idempotent.
  public List<String> createSitelinkAssets(
      String accessToken,
      String customerId,
      String loginCustomerId,
      List<GoogleSitelinkSpec> sitelinks) {
    JsonNode response =
        mutateAll(
            accessToken,
            customerId,
            loginCustomerId,
            "assets",
            sitelinkAssetOperations(objectMapper, sitelinks));
    List<String> resourceNames = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      resourceNames.add(result.path("resourceName").asText(null));
    }
    log.debug("Created {} sitelink assets for customer {}", resourceNames.size(), customerId);
    return resourceNames;
  }

  static ArrayNode sitelinkAssetOperations(
      ObjectMapper objectMapper, List<GoogleSitelinkSpec> sitelinks) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (GoogleSitelinkSpec spec : sitelinks) {
      // Asset.type is output-only — the assetData oneof implies it, and sending a type is
      // a 400.
      ObjectNode create = operations.addObject().putObject("create");
      ObjectNode sitelink = create.putObject("sitelinkAsset");
      sitelink.put("linkText", spec.linkText());
      if (spec.description1() != null) {
        sitelink.put("description1", spec.description1());
      }
      if (spec.description2() != null) {
        sitelink.put("description2", spec.description2());
      }
      ArrayNode finalUrls = create.putArray("finalUrls");
      spec.finalUrls().forEach(finalUrls::add);
      if (spec.finalMobileUrls() != null && !spec.finalMobileUrls().isEmpty()) {
        ArrayNode mobileUrls = create.putArray("finalMobileUrls");
        spec.finalMobileUrls().forEach(mobileUrls::add);
      }
    }
    return operations;
  }

  // A sitelink asset, unlike a text asset, is editable in place — which is what lets an edit
  // keep the sitelink's id, its links and its history instead of replacing it.
  public void updateSitelinkAsset(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String assetResourceName,
      GoogleSitelinkUpdateSpec spec) {
    ObjectNode operation = sitelinkAssetUpdateOperation(objectMapper, assetResourceName, spec);
    log.debug(
        "Updating sitelink asset {} for customer {} with mask {}",
        assetResourceName,
        customerId,
        operation.path("updateMask").asText());
    mutate(accessToken, customerId, loginCustomerId, "assets", operation);
  }

  // Only what the caller set reaches the body and the mask; every mask path is a leaf,
  // because Google rejects a mask naming a message rather than a field inside it.
  static ObjectNode sitelinkAssetUpdateOperation(
      ObjectMapper objectMapper, String assetResourceName, GoogleSitelinkUpdateSpec spec) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode update = operation.putObject("update");
    update.put("resourceName", assetResourceName);
    List<String> mask = new ArrayList<>();
    if (spec.linkText() != null || spec.description1() != null || spec.description2() != null) {
      ObjectNode sitelink = update.putObject("sitelinkAsset");
      if (spec.linkText() != null) {
        sitelink.put("linkText", spec.linkText());
        mask.add("sitelink_asset.link_text");
      }
      if (spec.description1() != null) {
        sitelink.put("description1", spec.description1());
        mask.add("sitelink_asset.description1");
      }
      if (spec.description2() != null) {
        sitelink.put("description2", spec.description2());
        mask.add("sitelink_asset.description2");
      }
    }
    if (spec.finalUrls() != null) {
      ArrayNode finalUrls = update.putArray("finalUrls");
      spec.finalUrls().forEach(finalUrls::add);
      mask.add("final_urls");
    }
    if (spec.finalMobileUrls() != null) {
      ArrayNode mobileUrls = update.putArray("finalMobileUrls");
      spec.finalMobileUrls().forEach(mobileUrls::add);
      mask.add("final_mobile_urls");
    }
    operation.put("updateMask", String.join(",", mask));
    return operation;
  }

  // One atomic mutate per level: the links to create and the links to remove travel together,
  // so a swap never leaves the campaign momentarily without the sitelink it is replacing.
  public void mutateSitelinkLinks(
      String accessToken,
      String customerId,
      String loginCustomerId,
      GoogleSitelinkLevel level,
      String ownerId,
      List<GoogleAssetLinkSpec> toLink,
      List<GoogleAssetLinkSpec> toUnlink) {
    mutateAll(
        accessToken,
        customerId,
        loginCustomerId,
        level.mutateService(),
        sitelinkLinkOperations(objectMapper, customerId, level, ownerId, toLink, toUnlink));
    log.debug(
        "Customer {} linked {} and unlinked {} sitelinks at {} {}",
        customerId,
        toLink.size(),
        toUnlink.size(),
        level,
        ownerId);
  }

  static ArrayNode sitelinkLinkOperations(
      ObjectMapper objectMapper,
      String customerId,
      GoogleSitelinkLevel level,
      String ownerId,
      List<GoogleAssetLinkSpec> toLink,
      List<GoogleAssetLinkSpec> toUnlink) {
    ArrayNode operations = objectMapper.createArrayNode();
    for (GoogleAssetLinkSpec spec : toLink) {
      ObjectNode create = operations.addObject().putObject("create");
      switch (level) {
        case CAMPAIGN ->
            create.put(
                "campaign",
                "customers/" + sanitizeId(customerId) + "/campaigns/" + sanitizeId(ownerId));
        case AD_GROUP ->
            create.put(
                "adGroup",
                "customers/" + sanitizeId(customerId) + "/adGroups/" + sanitizeId(ownerId));
        // A CustomerAsset names no owner: the account it is created under IS the owner.
        case ACCOUNT -> {}
      }
      create.put("asset", spec.assetResourceName());
      create.put("fieldType", sanitizeFieldType(spec.fieldType()));
    }
    for (GoogleAssetLinkSpec spec : toUnlink) {
      operations
          .addObject()
          .put("remove", sitelinkLinkResourceName(customerId, level, ownerId, spec));
    }
    return operations;
  }

  // The link's resource name is the ids it joins — except at account level, where there is no
  // owner id to join and the name is {assetId}~{FIELD_TYPE} alone.
  static String sitelinkLinkResourceName(
      String customerId, GoogleSitelinkLevel level, String ownerId, GoogleAssetLinkSpec spec) {
    String prefix = "customers/" + sanitizeId(customerId) + "/" + level.mutateService() + "/";
    String owner = level == GoogleSitelinkLevel.ACCOUNT ? "" : sanitizeId(ownerId) + "~";
    return prefix + owner + sanitizeId(spec.assetId()) + "~" + sanitizeFieldType(spec.fieldType());
  }

  // One image per request on purpose. imageAsset.data is base64, which inflates the body by
  // a third, so five 5 MB images in one mutate is a 34 MB request Google answers with 413.
  //
  // Google deduplicates an image asset by its CONTENT, so a create for bytes the account
  // already holds returns the existing asset's resource name rather than a second copy —
  // which is what lets an image edit diff on content it cannot otherwise see, and what lets
  // an unchanged image keep its link and its history.
  public String createImageAsset(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String assetName,
      byte[] imageBytes) {
    String resourceName =
        mutate(
            accessToken,
            customerId,
            loginCustomerId,
            "assets",
            imageAssetOperation(objectMapper, assetName, imageBytes));
    log.debug(
        "Created image asset {} ({} bytes) for customer {}",
        resourceName,
        imageBytes.length,
        customerId);
    return resourceName;
  }

  static ObjectNode imageAssetOperation(
      ObjectMapper objectMapper, String assetName, byte[] imageBytes) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("name", assetName);
    // fileSize, mimeType and fullSize are all output-only: only data may be sent.
    create.putObject("imageAsset").put("data", Base64.getEncoder().encodeToString(imageBytes));
    return operation;
  }

  public String createYoutubeVideoAsset(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String assetName,
      String youtubeVideoId) {
    ObjectNode operation = objectMapper.createObjectNode();
    ObjectNode create = operation.putObject("create");
    create.put("name", assetName);
    create.putObject("youtubeVideoAsset").put("youtubeVideoId", youtubeVideoId);
    String resourceName = mutate(accessToken, customerId, loginCustomerId, "assets", operation);
    log.debug("Created YouTube asset {} for customer {}", resourceName, customerId);
    return resourceName;
  }

  // A call to action is not a text asset, contrary to how it is often described: it is a
  // CallToActionType enum on its own asset kind, so there is no free-text version of it.
  public String createCallToActionAsset(
      String accessToken, String customerId, String loginCustomerId, String callToAction) {
    ObjectNode operation = objectMapper.createObjectNode();
    operation.putObject("create").putObject("callToActionAsset").put("callToAction", callToAction);
    String resourceName = mutate(accessToken, customerId, loginCustomerId, "assets", operation);
    log.debug("Created call-to-action asset {} for customer {}", resourceName, customerId);
    return resourceName;
  }

  // The whole asset swap in one assetGroupAssets:mutate, link creates before link removes.
  //
  // One request because AssetGroupAsset cannot be updated: replacing an asset is always an
  // add plus a drop, and a Google Ads mutate is atomic without partialFailure, so a single
  // request is the only way the asset group is never momentarily below Google's per-slot
  // minimum or above its maximum.
  public void mutateAssetGroupAssetLinks(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String assetGroupId,
      List<GoogleAssetLinkSpec> toLink,
      List<GoogleAssetLinkSpec> toUnlink) {
    mutateAll(
        accessToken,
        customerId,
        loginCustomerId,
        "assetGroupAssets",
        assetGroupAssetLinkOperations(objectMapper, customerId, assetGroupId, toLink, toUnlink));
    log.debug(
        "Asset group {} linked {} and unlinked {} assets for customer {}",
        assetGroupId,
        toLink.size(),
        toUnlink.size(),
        customerId);
  }

  static ArrayNode assetGroupAssetLinkOperations(
      ObjectMapper objectMapper,
      String customerId,
      String assetGroupId,
      List<GoogleAssetLinkSpec> toLink,
      List<GoogleAssetLinkSpec> toUnlink) {
    String assetGroupResourceName =
        "customers/" + sanitizeId(customerId) + "/assetGroups/" + sanitizeId(assetGroupId);
    ArrayNode operations = objectMapper.createArrayNode();
    for (GoogleAssetLinkSpec spec : toLink) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("assetGroup", assetGroupResourceName);
      create.put("asset", spec.assetResourceName());
      create.put("fieldType", sanitizeFieldType(spec.fieldType()));
    }
    for (GoogleAssetLinkSpec spec : toUnlink) {
      // An AssetGroupAsset has no id of its own: its resource name is the three ids it
      // joins, so a remove is constructed rather than read back.
      operations
          .addObject()
          .put(
              "remove",
              "customers/"
                  + sanitizeId(customerId)
                  + "/assetGroupAssets/"
                  + sanitizeId(assetGroupId)
                  + "~"
                  + sanitizeId(spec.assetId())
                  + "~"
                  + sanitizeFieldType(spec.fieldType()));
    }
    return operations;
  }

  // Replacing the search themes of an asset group. asset_group_signal supports create and
  // remove but never update, so a replace is a diff — the signals that dropped out are
  // removed and the new themes created — and both halves go in one atomic mutate so a
  // theme Google rejects on policy leaves the old set intact instead of half-replaced.
  public List<String> mutateAssetGroupSignals(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String assetGroupId,
      List<String> searchThemesToCreate,
      List<String> signalResourceNamesToRemove) {
    JsonNode response =
        mutateAll(
            accessToken,
            customerId,
            loginCustomerId,
            "assetGroupSignals",
            assetGroupSignalOperations(
                objectMapper,
                "customers/" + sanitizeId(customerId) + "/assetGroups/" + sanitizeId(assetGroupId),
                searchThemesToCreate,
                signalResourceNamesToRemove));
    List<String> created = new ArrayList<>();
    for (JsonNode result : response.path("results")) {
      created.add(result.path("resourceName").asText(null));
    }
    log.debug(
        "Asset group {} created {} and removed {} signals for customer {}",
        assetGroupId,
        searchThemesToCreate.size(),
        signalResourceNamesToRemove.size(),
        customerId);
    return created;
  }

  static ArrayNode assetGroupSignalOperations(
      ObjectMapper objectMapper,
      String assetGroupResourceName,
      List<String> searchThemesToCreate,
      List<String> signalResourceNamesToRemove) {
    ArrayNode operations = objectMapper.createArrayNode();
    // Removes first: the set is replaced, and a theme that is being re-created after a
    // removal must not collide with the row still holding it.
    for (String resourceName : signalResourceNamesToRemove) {
      operations.addObject().put("remove", resourceName);
    }
    for (String theme : searchThemesToCreate) {
      ObjectNode create = operations.addObject().putObject("create");
      create.put("assetGroup", assetGroupResourceName);
      create.putObject("searchTheme").put("text", theme);
    }
    return operations;
  }

  // Single-operation mutate; returns the created/updated resource name.
  private String mutate(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String service,
      ObjectNode operation) {
    ArrayNode operations = objectMapper.createArrayNode();
    operations.add(operation);
    JsonNode response = mutateAll(accessToken, customerId, loginCustomerId, service, operations);
    return response.path("results").path(0).path("resourceName").asText(null);
  }

  private JsonNode mutateAll(
      String accessToken,
      String customerId,
      String loginCustomerId,
      String service,
      ArrayNode operations) {
    ObjectNode body = objectMapper.createObjectNode();
    body.set("operations", operations);
    return post(
        apiBase() + "/customers/" + sanitizeId(customerId) + "/" + service + ":mutate",
        accessToken,
        loginCustomerId,
        body,
        "Google Ads " + service + ":mutate",
        GoogleAdsRequestContext.of(customerId, loginCustomerId, body));
  }

  // The bulk mutate endpoint. Google refuses to create a Performance Max asset group
  // through assetGroups:mutate — the group and the AssetGroupAsset links satisfying its
  // asset minimums have to land in one request — so this is the only way to build one.
  //
  // Being one request also makes it atomic, which is why the Performance Max creates need
  // no equivalent of GoogleCreateCampaignTool's rollback: there is no half-built campaign
  // to unwind. partialFailure is deliberately absent for the same reason.
  //
  // Operations reference each other through temporary resource names — see
  // GoogleAdsTempIds — and each must be defined before it is referenced.
  private JsonNode mutateGoogleAds(
      String accessToken, String customerId, String loginCustomerId, ArrayNode mutateOperations) {
    ObjectNode body = objectMapper.createObjectNode();
    body.set("mutateOperations", mutateOperations);
    // Resource names are all the callers here need, and the alternative (MUTABLE_RESOURCE)
    // echoes every created asset back, including the base64 of an image.
    body.put("responseContentType", "RESOURCE_NAME_ONLY");
    return post(
        apiBase() + "/customers/" + sanitizeId(customerId) + "/googleAds:mutate",
        accessToken,
        loginCustomerId,
        body,
        "Google Ads googleAds:mutate",
        GoogleAdsRequestContext.of(customerId, loginCustomerId, body));
  }

  private JsonNode get(
      String url,
      String accessToken,
      String loginCustomerId,
      String operation,
      GoogleAdsRequestContext context) {
    return send(
        requestBuilder(url, accessToken, loginCustomerId).GET().build(), operation, context);
  }

  private JsonNode post(
      String url,
      String accessToken,
      String loginCustomerId,
      JsonNode body,
      String operation,
      GoogleAdsRequestContext context) {
    return send(
        requestBuilder(url, accessToken, loginCustomerId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build(),
        operation,
        context);
  }

  private HttpRequest.Builder requestBuilder(
      String url, String accessToken, String loginCustomerId) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("developer-token", config.developerToken());
    if (loginCustomerId != null && !loginCustomerId.isBlank()) {
      builder.header("login-customer-id", sanitizeId(loginCustomerId));
    }
    return builder;
  }

  private JsonNode send(HttpRequest request, String operation, GoogleAdsRequestContext context) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode body =
          response.body() == null || response.body().isBlank()
              ? objectMapper.createObjectNode()
              : objectMapper.readTree(response.body());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return body;
      }
      // searchStream errors arrive wrapped in an array like its results do.
      JsonNode errorHolder = body.isArray() ? body.path(0) : body;
      String message = adsErrorMessage(errorHolder);
      String status = errorHolder.path("error").path("status").asText(null);
      List<String> codes = adsErrorCodes(errorHolder);
      String requestId = response.headers().firstValue("request-id").orElse(null);
      logFailure(
          operation,
          String.valueOf(response.statusCode()),
          status,
          codes,
          requestId,
          message,
          context,
          adsErrorOperationIndex(errorHolder));
      throw new GoogleAdsApiException(
          operation + " failed: " + message, response.statusCode(), status, codes, requestId);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      logFailure(
          operation,
          "-",
          e.getClass().getSimpleName(),
          List.of(),
          null,
          e.getMessage(),
          context,
          OptionalInt.empty());
      throw new GoogleAdsApiException(operation + " failed: " + e.getMessage(), 0, null);
    }
  }

  // One self-contained block per failure: who it was for, which API version, Google's own
  // request id, and the request itself. Without the request a rejected field or operation
  // index means nothing without reading the source that built it.
  private void logFailure(
      String operation,
      String httpStatus,
      String errorStatus,
      List<String> codes,
      String requestId,
      String message,
      GoogleAdsRequestContext context,
      OptionalInt failingOperationIndex) {
    GoogleAdsRequestContext safe =
        context == null ? GoogleAdsRequestContext.ofAccount(null, null) : context;
    log.warn(
        "GADS-ERR {} failed HTTP {} {}\n  customer={} login={} version={} requestId={} codes={}"
            + "\n  message={}\n  request={}",
        operation,
        httpStatus,
        errorStatus == null ? "-" : errorStatus,
        safe.customerId() == null ? "-" : safe.customerId(),
        safe.loginCustomerId() == null ? "-" : safe.loginCustomerId(),
        config.apiVersion(),
        requestId == null ? "-" : requestId,
        codes,
        GoogleAdsRequestLog.scrub(message),
        GoogleAdsRequestLog.scrub(
            GoogleAdsRequestLog.describe(safe.body(), failingOperationIndex)));
  }

  // Google buries the actionable text in details[].errors[].message (GoogleAdsFailure);
  // the top-level message is often just "Request contains an invalid argument."
  static String adsErrorMessage(JsonNode body) {
    JsonNode error = body.path("error");
    if (error.isMissingNode()) {
      // No GoogleAdsFailure to unwrap, so the raw body is all there is — capped, because
      // it lands both in the log and in the message the model is shown.
      return GoogleAdsRequestLog.truncate(body.toString(), GoogleAdsRequestLog.MAX_REQUEST_CHARS);
    }
    StringBuilder sb = new StringBuilder(error.path("message").asText());
    String status = error.path("status").asText(null);
    if (status != null) {
      sb.append(" (").append(status).append(")");
    }
    for (JsonNode detail : error.path("details")) {
      for (JsonNode adsError : detail.path("errors")) {
        String message = adsError.path("message").asText(null);
        if (message != null) {
          sb.append(" — ").append(message);
        }
        String fieldPath = adsErrorFieldPath(adsError);
        if (fieldPath != null) {
          sb.append(" [").append(fieldPath).append("]");
        }
      }
    }
    return sb.toString();
  }

  // Google's machine-readable error codes, one per details[].errors[] entry. errorCode
  // is a oneof, so the field name is part of the identity — "REQUIRED" alone would
  // collide across fieldError, adGroupError and a dozen others. Returned qualified as
  // "authenticationError.NOT_ADS_USER" so callers can match one exact code.
  static List<String> adsErrorCodes(JsonNode body) {
    List<String> codes = new ArrayList<>();
    for (JsonNode detail : body.path("error").path("details")) {
      for (JsonNode adsError : detail.path("errors")) {
        Iterator<Map.Entry<String, JsonNode>> fields = adsError.path("errorCode").fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          if (field.getValue().isValueNode()) {
            codes.add(field.getKey() + "." + field.getValue().asText());
          }
        }
      }
    }
    return codes;
  }

  // Which operation of a mutate Google rejected. The whole batch fails on one bad
  // operation, and the index is the only thing tying the error to it — "operations[14]"
  // is unactionable while the operations themselves are not logged.
  //
  // Two field names, because a per-service mutate reports the index under "operations"
  // while the bulk googleAds:mutate reports it under "mutate_operations". Accepting only
  // the first left every bulk failure — which is every Performance Max create — with no
  // index and therefore no failing operation in the log.
  static OptionalInt adsErrorOperationIndex(JsonNode body) {
    for (JsonNode detail : body.path("error").path("details")) {
      for (JsonNode adsError : detail.path("errors")) {
        for (JsonNode element : adsError.path("location").path("fieldPathElements")) {
          String fieldName = element.path("fieldName").asText(null);
          if (("operations".equals(fieldName) || "mutate_operations".equals(fieldName))
              && element.hasNonNull("index")) {
            return OptionalInt.of(element.get("index").asInt());
          }
        }
      }
    }
    return OptionalInt.empty();
  }

  // Google names the offending field only in location.fieldPathElements. Without it a
  // message like "The required field was not present." says nothing about which field,
  // which is unactionable both in the logs and in the tool error the model sees.
  private static String adsErrorFieldPath(JsonNode adsError) {
    StringBuilder path = new StringBuilder();
    for (JsonNode element : adsError.path("location").path("fieldPathElements")) {
      String fieldName = element.path("fieldName").asText(null);
      if (fieldName == null) {
        continue;
      }
      if (path.length() > 0) {
        path.append('.');
      }
      path.append(fieldName);
      if (element.hasNonNull("index")) {
        path.append('[').append(element.get("index").asInt()).append(']');
      }
    }
    return path.length() == 0 ? null : path.toString();
  }

  public static long microsToCents(long micros) {
    return micros / 10_000;
  }

  public static long centsToMicros(long cents) {
    return cents * 10_000;
  }

  // Ids are interpolated into GAQL and resource paths; only digits are legal.
  static String sanitizeId(String id) {
    String cleaned = id.replace("-", "");
    if (!cleaned.matches("\\d+")) {
      throw new GoogleAdsApiException("Invalid Google Ads id: " + id, 400, "INVALID_ARGUMENT");
    }
    return cleaned;
  }

  private static String sanitizeDate(String date) {
    if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
      throw new GoogleAdsApiException(
          "Invalid date (expected YYYY-MM-DD): " + date, 400, "INVALID_ARGUMENT");
    }
    return date;
  }

  // Asset field types are interpolated into an assetGroupAssets resource name, where
  // sanitizeId does not apply — the segment is HEADLINE, not digits. Enum names only.
  static String sanitizeFieldType(String fieldType) {
    if (fieldType == null || !fieldType.matches("[A-Z_]+")) {
      throw new GoogleAdsApiException(
          "Invalid asset field type: " + fieldType, 400, "INVALID_ARGUMENT");
    }
    return fieldType;
  }

  private static String sanitizeKeyword(String keyword) {
    if (keyword == null || !keyword.matches("[A-Z0-9_]+")) {
      throw new GoogleAdsApiException("Invalid date preset: " + keyword, 400, "INVALID_ARGUMENT");
    }
    return keyword;
  }
}
