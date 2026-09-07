package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleCampaignBiddingSpec;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleCampaignGoalsDto;
import com.loomascale.googleads.client.dto.GoogleConversionGoalDto;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// The campaign-level half of Google bidding: the Max CPC limit of a Maximize clicks
// campaign, the target CPA of a Maximize conversions campaign, and the switch between
// the three strategies. A Max CPC limit set below the market bid starves the campaign of
// impressions, and until this tool existed there was no way to raise it — nor to reach
// Manual CPC, without which the bids set by google_update_ad_group and
// google_update_keyword never affect delivery, nor Maximize conversions, without which
// a campaign cannot optimize towards a conversion goal at all.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateCampaignBiddingTool implements AdsTool {

  private static final String MAXIMIZE_CLICKS = "MAXIMIZE_CLICKS";
  private static final List<String> STRATEGIES =
      List.of(
          MAXIMIZE_CLICKS,
          GoogleAdsApiClient.STRATEGY_MANUAL_CPC,
          GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS,
          GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE);

  // Performance Max bids on conversions or on conversion value and nothing else. Manual CPC
  // and Maximize clicks are not offered on the channel at all, and Google answers the attempt
  // with an opaque bidding-strategy error, so the refusal is named here instead. Only PMax is
  // policed this way: for the other channels the long tail of Google's rules is Google's
  // business, and over-policing would refuse valid combinations.
  private static final List<String> PMAX_STRATEGIES =
      List.of(
          GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS,
          GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE);

  // A target ROAS is a ratio, so there is no budget to sanity-check it against. This bound is
  // about naming the failure before it happens: Google accepts an absurd target and then
  // simply never spends.
  private static final double MAX_TARGET_ROAS = 100.0;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_campaign_bidding";
  }

  @Override
  public String description() {
    return "Change the bidding settings of a Google Ads campaign: set or clear the Max CPC limit of"
        + " a Maximize clicks campaign, and/or switch the campaign between Maximize clicks and"
        + " Manual CPC. Use this when a campaign gets few or no impressions because its Max CPC"
        + " limit is below the market bid, or before setting manual bids with"
        + " google_update_ad_group or google_update_keyword, which Google only honours under Manual"
        + " CPC. Also switches a campaign to Maximize conversions, optionally with a target CPA —"
        + " the strategy that optimizes towards the campaign's conversion goals, which"
        + " google_update_campaign_conversion_goals selects. Also switches a campaign to"
        + " Maximize conversion value, optionally with a target ROAS given as a RATIO (pass 4 to"
        + " mean 400%) — the strategy Performance Max and value-based search campaigns use to bid"
        + " on revenue rather than on conversion count. Refuses campaigns governed by a"
        + " portfolio bidding strategy. For daily budgets use google_update_budget.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign whose bidding to update.");
    ObjectNode strategy =
        McpSchemas.prop(
            schema,
            "strategy",
            "string",
            "Bidding strategy to switch the campaign to. Omit to keep the current one. MANUAL_CPC"
                + " makes ad group and keyword bids take effect; MAXIMIZE_CLICKS lets Google bid"
                + " automatically up to the Max CPC limit; MAXIMIZE_CONVERSIONS optimizes towards"
                + " the campaign's biddable conversion goals and needs at least one of them.");
    McpSchemas.enumValues(strategy, STRATEGIES);
    McpSchemas.prop(
        schema,
        "max_cpc",
        "number",
        McpSchemas.moneyInputDescription(
            "New Max CPC limit for Maximize clicks — the most Google may bid for one click. Pass"
                + " null to remove the limit entirely, which lets Google bid whatever the auction"
                + " costs, up to the daily budget. Not accepted with MANUAL_CPC, which has no"
                + " campaign-level limit."));
    McpSchemas.prop(
        schema,
        "target_cpa",
        "number",
        McpSchemas.moneyInputDescription(
            "Target cost per conversion for Maximize conversions — the average Google aims to pay"
                + " for one conversion. Pass null to remove it and let Google spend the whole daily"
                + " budget chasing conversions. Only accepted with MAXIMIZE_CONVERSIONS."));
    McpSchemas.prop(
        schema,
        "target_roas",
        "number",
        "Target return on ad spend for Maximize conversion value, as a RATIO — not money, and not"
            + " a percentage. Pass 4 to mean 4 units of conversion value for every 1 unit of"
            + " spend, which Google Ads displays as 400%; pass 0.5 to accept half. Never pass 400,"
            + " and never pass an amount in the account currency. Only accepted with"
            + " MAXIMIZE_CONVERSION_VALUE. Pass null to remove the target and let Google chase the"
            + " most conversion value the daily budget can buy.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was updated.");
    McpSchemas.nullableProp(
        schema, "biddingStrategyType", "string", "Bidding strategy now in effect.");
    McpSchemas.nullableProp(
        schema,
        "previousBiddingStrategyType",
        "string",
        "Bidding strategy the campaign had before this call.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(
        schema, "maxCpc", "Max CPC limit now in effect, null when the campaign has none.");
    McpSchemas.moneyProp(
        schema, "targetCpa", "Target CPA now in effect, null when the campaign has none.");
    // Deliberately not moneyProp: that splices the currency sentence onto the description,
    // and a target ROAS is a ratio with no currency at all.
    McpSchemas.nullableProp(
        schema,
        "targetRoas",
        "number",
        "Target ROAS now in effect as a ratio, where 4 means 400%. Null when the campaign has"
            + " none.");
    McpSchemas.required(schema, "campaignId", "biddingStrategyType");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: a higher Max CPC limit spends the daily budget faster, and a strategy
    // switch changes how the campaign delivers. Idempotent: it sets absolute values, so
    // a repeat call is a no-op.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("campaign_id")) {
      throw new McpToolException("campaign_id is required.");
    }
    String campaignId = args.get("campaign_id").asText();
    // An explicit null max_cpc is the request to remove the limit, so presence and
    // non-nullness mean different things here. Same for target_cpa.
    boolean maxCpcGiven = args.has("max_cpc");
    boolean clearMaxCpc = maxCpcGiven && args.get("max_cpc").isNull();
    boolean targetCpaGiven = args.has("target_cpa");
    boolean clearTargetCpa = targetCpaGiven && args.get("target_cpa").isNull();
    boolean targetRoasGiven = args.has("target_roas");
    boolean clearTargetRoas = targetRoasGiven && args.get("target_roas").isNull();
    String requestedStrategy = requestedStrategy(args);
    if (requestedStrategy == null && !maxCpcGiven && !targetCpaGiven && !targetRoasGiven) {
      throw new McpToolException(
          "Provide strategy, max_cpc, target_cpa, target_roas, or a combination — there is nothing"
              + " to change.");
    }
    int amountsGiven = (maxCpcGiven ? 1 : 0) + (targetCpaGiven ? 1 : 0) + (targetRoasGiven ? 1 : 0);
    if (amountsGiven > 1) {
      throw new McpToolException(
          "max_cpc belongs to Maximize clicks, target_cpa to Maximize conversions and target_roas"
              + " to Maximize conversion value, so a campaign never carries more than one of them."
              + " Pass one.");
    }
    if (maxCpcGiven && GoogleAdsApiClient.STRATEGY_MANUAL_CPC.equals(requestedStrategy)) {
      throw new McpToolException(
          "Manual CPC has no campaign-level Max CPC limit. Switch the campaign to MANUAL_CPC"
              + " without max_cpc, then set bids with google_update_ad_group or"
              + " google_update_keyword.");
    }
    if (maxCpcGiven && GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS.equals(requestedStrategy)) {
      throw new McpToolException(
          "Maximize conversions has no Max CPC limit — Google bids whatever a conversion is worth."
              + " Pass target_cpa to cap the average cost per conversion instead.");
    }
    if (maxCpcGiven
        && GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE.equals(requestedStrategy)) {
      throw new McpToolException(
          "Maximize conversion value has no Max CPC limit — Google bids what the predicted"
              + " conversion value is worth. Pass target_roas to set the return you need instead.");
    }
    if (targetCpaGiven
        && requestedStrategy != null
        && !GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS.equals(requestedStrategy)) {
      throw new McpToolException(
          "target_cpa only applies to MAXIMIZE_CONVERSIONS. " + requestedStrategy + " has none.");
    }
    if (targetRoasGiven
        && requestedStrategy != null
        && !GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE.equals(requestedStrategy)) {
      throw new McpToolException(
          "target_roas only applies to MAXIMIZE_CONVERSION_VALUE. "
              + requestedStrategy
              + " has none.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    GoogleCampaignDto campaign =
        support.requireCampaign(connection, token, customerId, loginCustomerId, campaignId);
    if (campaign.biddingStrategyResourceName() != null) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" is governed by a portfolio bidding strategy that also drives other campaigns,"
              + " so its bidding does not live on the campaign and this server will not change it"
              + " here. Adjust the strategy in Google Ads directly.");
    }
    if (GoogleAdsEditSupport.PERFORMANCE_MAX.equals(campaign.channelType())
        && requestedStrategy != null
        && !PMAX_STRATEGIES.contains(requestedStrategy)) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" is a Performance Max campaign, which bids only on conversions"
              + " (MAXIMIZE_CONVERSIONS, optionally with target_cpa) or on conversion value"
              + " (MAXIMIZE_CONVERSION_VALUE, optionally with target_roas). MANUAL_CPC and"
              + " MAXIMIZE_CLICKS are not available on Performance Max at all.");
    }
    String currentStrategy = campaign.biddingStrategyType();
    if (maxCpcGiven
        && requestedStrategy == null
        && !GoogleAdsApiClient.STRATEGY_TARGET_SPEND.equals(currentStrategy)) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" uses "
              + (currentStrategy == null ? "another bidding strategy" : currentStrategy)
              + ", which has no Max CPC limit. Pass strategy=MAXIMIZE_CLICKS to move it to Maximize"
              + " clicks with that limit, or set the strategy's own targets in Google Ads.");
    }
    if (targetCpaGiven
        && requestedStrategy == null
        && !GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS.equals(currentStrategy)) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" uses "
              + (currentStrategy == null ? "another bidding strategy" : currentStrategy)
              + ", which has no target CPA. Pass strategy=MAXIMIZE_CONVERSIONS to move it to"
              + " Maximize conversions with that target.");
    }

    if (targetRoasGiven
        && requestedStrategy == null
        && !GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE.equals(currentStrategy)) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" uses "
              + (currentStrategy == null ? "another bidding strategy" : currentStrategy)
              + ", which has no target ROAS. Pass strategy=MAXIMIZE_CONVERSION_VALUE to move it to"
              + " Maximize conversion value with that target.");
    }

    Long ceilingCents =
        maxCpcGiven && !clearMaxCpc
            ? ceilingCents(args.get("max_cpc").asDouble(), campaign, currency)
            : null;
    Long targetCpaCents =
        targetCpaGiven && !clearTargetCpa
            ? targetCpaCents(args.get("target_cpa").asDouble())
            : null;
    Double targetRoas =
        targetRoasGiven && !clearTargetRoas ? targetRoas(args.get("target_roas").asDouble()) : null;
    // TARGET_SPEND is what Google calls Maximize clicks; the others keep their names.
    String targetStrategy =
        requestedStrategy == null
            ? currentStrategy
            : (MAXIMIZE_CLICKS.equals(requestedStrategy)
                ? GoogleAdsApiClient.STRATEGY_TARGET_SPEND
                : requestedStrategy);
    boolean keepStrategy = targetStrategy.equals(currentStrategy);
    // The client always masks the strategy's own leaf field, so switching to Maximize
    // clicks without a max_cpc clears any limit the campaign carried; keeping the
    // strategy and omitting max_cpc would clear it too, which is never what "no change"
    // means, so there is nothing to send then. Same for maximize_conversions and its
    // target CPA.
    if (keepStrategy && !maxCpcGiven && !targetCpaGiven && !targetRoasGiven) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" already uses "
              + currentStrategy
              + ", so there is nothing to change. Pass "
              + nothingToChangeHint(currentStrategy));
    }
    // Both conversion strategies are refused by Google without something biddable to
    // optimize towards, so both get the same pre-check.
    if (PMAX_STRATEGIES.contains(targetStrategy) && !keepStrategy) {
      requireABiddableGoal(connection, token, customerId, loginCustomerId, campaign);
    }

    String argsSummary =
        "campaign="
            + campaignId
            + ";strategy="
            + targetStrategy
            + ";ceilingCents="
            + ceilingCents
            + ";targetCpaCents="
            + targetCpaCents
            + ";targetRoas="
            + targetRoas
            + ";keep="
            + keepStrategy;
    log.debug(
        "google_update_campaign_bidding user={} {} (was {})", userId, argsSummary, currentStrategy);
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateCampaignBidding(
                    token,
                    customerId,
                    loginCustomerId,
                    campaignId,
                    new GoogleCampaignBiddingSpec(
                        targetStrategy,
                        ceilingCents == null
                            ? null
                            : GoogleAdsApiClient.centsToMicros(ceilingCents),
                        targetCpaCents == null
                            ? null
                            : GoogleAdsApiClient.centsToMicros(targetCpaCents),
                        // Deliberately NOT through centsToMicros: a target ROAS is a ratio.
                        targetRoas));
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_campaign_bidding</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaign.name()
              + " ("
              + campaignId
              + ")\nStrategy: "
              + currentStrategy
              + " → "
              + targetStrategy
              + "\nMax CPC: "
              + amountDescription(maxCpcGiven, clearMaxCpc, ceilingCents, currency)
              + "\nTarget CPA: "
              + amountDescription(targetCpaGiven, clearTargetCpa, targetCpaCents, currency)
              + "\nTarget ROAS: "
              + roasDescription(targetRoasGiven, clearTargetRoas, targetRoas));

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("campaignId", campaignId);
      structured.put("biddingStrategyType", targetStrategy);
      structured.put("previousBiddingStrategyType", currentStrategy);
      structured.put("currency", currency);
      Long effectiveCeiling =
          GoogleAdsApiClient.STRATEGY_TARGET_SPEND.equals(targetStrategy)
              ? (maxCpcGiven ? ceilingCents : campaign.cpcBidCeilingCents())
              : null;
      if (effectiveCeiling == null) {
        structured.putNull("maxCpc");
      } else {
        structured.put("maxCpc", Money.majorUnits(effectiveCeiling));
      }
      // Unlike the Max CPC limit, the campaign list does not carry a target CPA, so the
      // only value we can state is the one this call wrote.
      if (targetCpaCents == null) {
        structured.putNull("targetCpa");
      } else {
        structured.put("targetCpa", Money.majorUnits(targetCpaCents));
      }
      if (targetRoas == null) {
        structured.putNull("targetRoas");
      } else {
        // A ratio, so no Money.display and no currency anywhere near it.
        structured.put("targetRoas", targetRoas);
      }
      return ToolResult.ok(
          resultText(
              campaign,
              currentStrategy,
              targetStrategy,
              maxCpcGiven,
              clearMaxCpc,
              ceilingCents,
              targetCpaGiven,
              clearTargetCpa,
              targetCpaCents,
              currency,
              connection,
              token,
              customerId,
              loginCustomerId),
          structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, false, e.getMessage());
      throw e;
    }
  }

  private Long ceilingCents(double maxCpc, GoogleCampaignDto campaign, String currency) {
    if (maxCpc <= 0) {
      throw new McpToolException(
          "max_cpc must be greater than zero. Pass null to remove the limit instead.");
    }
    // Same rule the ad group and keyword bids follow: a ceiling above the whole daily
    // budget would let a single click spend the day.
    return support.requireSaneBidCents(maxCpc, campaign, currency);
  }

  // A target CPA above the daily budget is ordinary — a lead worth 50 is chased on a
  // budget of 20 a day — so unlike a click bid this is only checked for being positive.
  private long targetCpaCents(double targetCpa) {
    if (targetCpa <= 0) {
      throw new McpToolException(
          "target_cpa must be greater than zero. Pass null to remove the target instead.");
    }
    return Money.minorUnits(targetCpa);
  }

  // A ratio, not money: no budget comparison applies, so unlike a click bid this is only
  // bounded for plausibility. Google accepts a target of 100x and then simply does not spend,
  // which is the failure this names before it happens.
  private double targetRoas(double value) {
    if (value <= 0) {
      throw new McpToolException(
          "target_roas must be greater than zero. Pass null to remove the target instead.");
    }
    if (value > MAX_TARGET_ROAS) {
      throw new McpToolException(
          "A target ROAS of "
              + value
              + " asks for "
              + value
              + " units of conversion value for every unit of spend. Google will simply not spend"
              + " the budget at a target it cannot hit. Pass the return you actually need, e.g. 4"
              + " for 400%.");
    }
    return value;
  }

  private String nothingToChangeHint(String currentStrategy) {
    if (GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS.equals(currentStrategy)) {
      return "target_cpa to change its target CPA.";
    }
    if (GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSION_VALUE.equals(currentStrategy)) {
      return "target_roas to change its target ROAS.";
    }
    return "max_cpc to change its Max CPC limit.";
  }

  private String roasDescription(boolean given, boolean cleared, Double targetRoas) {
    if (!given) {
      return "unchanged";
    }
    return cleared || targetRoas == null ? "removed" : String.valueOf(targetRoas);
  }

  // Maximize conversions with nothing biddable to optimize towards is the failure this
  // check exists to name: Google rejects the switch, and the reason is a goal setting
  // two screens away from bidding.
  private void requireABiddableGoal(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      GoogleCampaignDto campaign) {
    GoogleCampaignGoalsDto campaignGoals =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listCampaignConversionGoals(
                        token, customerId, loginCustomerId, campaign.id()));
    List<GoogleConversionGoalDto> effective =
        campaignGoals.usesCampaignGoals()
            ? campaignGoals.goals()
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .listCustomerConversionGoals(token, customerId, loginCustomerId));
    if (effective.stream().anyMatch(GoogleConversionGoalDto::biddable)) {
      return;
    }
    throw new McpToolException(
        "Campaign \""
            + campaign.name()
            + "\" has no biddable conversion goal, so Google will not let it optimize for"
            + " conversions. Pick the goals it should optimize for with"
            + " google_update_campaign_conversion_goals, then switch the bidding again.");
  }

  private String requestedStrategy(JsonNode args) {
    if (!args.hasNonNull("strategy")) {
      return null;
    }
    String strategy = args.get("strategy").asText().trim().toUpperCase();
    if (!STRATEGIES.contains(strategy)) {
      throw new McpToolException("strategy must be one of: " + String.join(", ", STRATEGIES));
    }
    return strategy;
  }

  private String amountDescription(
      boolean given, boolean cleared, Long amountCents, String currency) {
    if (!given) {
      return "unchanged";
    }
    return cleared ? "removed" : Money.display(amountCents, currency);
  }

  private String resultText(
      GoogleCampaignDto campaign,
      String currentStrategy,
      String targetStrategy,
      boolean maxCpcGiven,
      boolean clearMaxCpc,
      Long ceilingCents,
      boolean targetCpaGiven,
      boolean clearTargetCpa,
      Long targetCpaCents,
      String currency,
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId) {
    StringBuilder text = new StringBuilder("Campaign \"" + campaign.name() + "\"");
    boolean switched = !targetStrategy.equals(currentStrategy);
    if (switched) {
      text.append(" switched from ")
          .append(currentStrategy == null ? "its previous bidding strategy" : currentStrategy)
          .append(" to ")
          .append(targetStrategy);
    }
    if (maxCpcGiven) {
      text.append(switched ? " and its" : " has its")
          .append(" Max CPC limit ")
          .append(clearMaxCpc ? "removed" : "set to " + Money.display(ceilingCents, currency));
    }
    if (targetCpaGiven) {
      text.append(switched ? " and its" : " has its")
          .append(" target CPA ")
          .append(clearTargetCpa ? "removed" : "set to " + Money.display(targetCpaCents, currency));
    }
    text.append(".");
    if (clearMaxCpc) {
      text.append(
          " Google may now bid whatever the auction costs for a click, limited only by the daily"
              + " budget.");
    }
    if (GoogleAdsApiClient.STRATEGY_MANUAL_CPC.equals(targetStrategy)) {
      text.append(
          " Manual CPC bids now drive delivery: set them with google_update_ad_group or"
              + " google_update_keyword.");
      if (noAdGroupCarriesABid(connection, token, customerId, loginCustomerId, campaign.id())) {
        text.append(
            " No ad group in this campaign has a CPC bid yet, so it will not serve until one is"
                + " set.");
      }
    }
    if (GoogleAdsApiClient.STRATEGY_MAXIMIZE_CONVERSIONS.equals(targetStrategy) && switched) {
      text.append(
          " Delivery now follows the campaign's biddable conversion goals — check them with"
              + " google_list_conversion_actions and change them with"
              + " google_update_campaign_conversion_goals.");
    }
    return text.toString();
  }

  // Manual CPC with no bid anywhere is a campaign that silently stops serving, so the
  // result has to say so rather than report a clean success.
  private boolean noAdGroupCarriesABid(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String campaignId) {
    List<GoogleAdGroupDto> adGroups =
        adsService.call(
            connection,
            () -> adsService.client().listAdGroups(token, customerId, loginCustomerId, campaignId));
    return adGroups.stream().allMatch(adGroup -> adGroup.cpcBidCents() == null);
  }
}
