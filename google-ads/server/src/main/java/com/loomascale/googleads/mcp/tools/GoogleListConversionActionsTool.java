package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleCampaignGoalsDto;
import com.loomascale.googleads.client.dto.GoogleConversionActionDto;
import com.loomascale.googleads.client.dto.GoogleConversionActionQuery;
import com.loomascale.googleads.client.dto.GoogleConversionActionStatsDto;
import com.loomascale.googleads.client.dto.GoogleConversionGoalDto;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// What the account counts as a conversion, how much of it actually happened, and
// which of it bidding is allowed to optimize towards. Several queries in one call on
// purpose: the configuration alone cannot tell you a primary action stopped firing,
// the volume alone cannot tell you why the number is wrong, and neither explains an
// action that is enabled and primary yet ignored — that is a conversion goal whose
// category is not biddable. Money is in minor units of the account currency.
@Component
@RequiredArgsConstructor
public class GoogleListConversionActionsTool implements AdsTool {

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

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_conversion_actions";
  }

  @Override
  public String description() {
    return "Audit how a Google Ads account counts conversions: every conversion action with its"
        + " category, type, status, whether it is primary (so it feeds bidding and the conversions"
        + " column), its counting type, attribution model, value settings and lookback windows,"
        + " plus how many conversions each one actually recorded over the date range. Use this to"
        + " find duplicated actions, actions marked primary that should not be, missing"
        + " conversion values, and primary actions that have stopped firing. Also returns the"
        + " account's conversion goals — the (category, origin) groups bidding optimizes towards —"
        + " and, when campaign_id is given, that campaign's own goals and whether it uses"
        + " campaign-specific goal settings; change those with"
        + " google_update_campaign_conversion_goals. For the performance numbers those conversions"
        + " roll up into, use google_get_insights.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    ObjectNode preset =
        McpSchemas.prop(
            schema,
            "date_preset",
            "string",
            "Range the conversion counts cover. Default LAST_30_DAYS. Ignored when since/until"
                + " are set.");
    McpSchemas.enumValues(preset, DATE_PRESETS);
    McpSchemas.prop(schema, "since", "string", "Custom range start, YYYY-MM-DD.");
    McpSchemas.prop(schema, "until", "string", "Custom range end, YYYY-MM-DD.");
    McpSchemas.prop(
        schema,
        "campaign_id",
        "string",
        "Also report this campaign's conversion goals and whether it uses campaign-specific goal"
            + " settings. Omit for the account-level picture only.");
    McpSchemas.prop(
        schema,
        "include_removed",
        "boolean",
        "Also list removed conversion actions. Default false — a removed action explains history"
            + " but not what bidding is being fed today.");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "customerId", "string", "Customer account that was audited.");
    McpSchemas.prop(schema, "dateRange", "string", "Range the conversion counts cover.");
    ObjectNode action =
        McpSchemas.objectArrayProp(schema, "conversionActions", "One entry per conversion action.");
    McpSchemas.nullableProp(action, "id", "string", "Conversion action id.");
    McpSchemas.nullableProp(action, "name", "string", "Conversion action name.");
    McpSchemas.nullableProp(
        action, "category", "string", "What the action represents, e.g. PURCHASE or PAGE_VIEW.");
    McpSchemas.nullableProp(
        action, "type", "string", "How it is tracked, e.g. WEBPAGE or GOOGLE_ANALYTICS_4_CUSTOM.");
    McpSchemas.nullableProp(action, "status", "string", "ENABLED, REMOVED or HIDDEN.");
    McpSchemas.nullableProp(
        action,
        "primaryForGoal",
        "boolean",
        "True when this action feeds Smart Bidding and the main conversions column.");
    McpSchemas.nullableProp(
        action,
        "countingType",
        "string",
        "ONE_PER_CLICK or MANY_PER_CLICK. MANY_PER_CLICK on a purchase-style action inflates the"
            + " count when a user converts twice.");
    McpSchemas.nullableProp(action, "attributionModel", "string", "Attribution model in use.");
    McpSchemas.moneyProp(action, "defaultValue", "Flat value assigned when no value is sent.");
    McpSchemas.nullableProp(
        action,
        "defaultCurrencyCode",
        "string",
        "Currency of defaultValue, which may differ from the account currency.");
    McpSchemas.nullableProp(
        action,
        "alwaysUseDefaultValue",
        "boolean",
        "True when every conversion is recorded at the default value, so real order values are"
            + " ignored and value-based bidding has nothing to optimize.");
    McpSchemas.nullableProp(
        action, "clickThroughLookbackDays", "integer", "Click-through conversion window in days.");
    McpSchemas.nullableProp(
        action, "viewThroughLookbackDays", "integer", "View-through conversion window in days.");
    McpSchemas.nullableProp(
        action,
        "includeInConversionsMetric",
        "boolean",
        "True when this action is included in the reported conversions metric.");
    McpSchemas.nullableProp(
        action, "origin", "string", "Where the action came from, e.g. GOOGLE_ANALYTICS.");
    McpSchemas.prop(
        action,
        "allConversions",
        "number",
        "Conversions recorded over the range, all actions counted.");
    McpSchemas.moneyProp(
        action, "allConversionsValue", "Conversion value recorded over the range.");
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    ObjectNode goal =
        McpSchemas.objectArrayProp(
            schema,
            "conversionGoals",
            "Account-level conversion goals. A conversion action only feeds Smart Bidding and the"
                + " conversions column when the goal for its category and origin is biddable.");
    McpSchemas.prop(goal, "category", "string", "Conversion goal category, e.g. SIGNUP.");
    McpSchemas.prop(goal, "origin", "string", "Where the conversions come from, e.g. WEBSITE.");
    McpSchemas.prop(goal, "biddable", "boolean", "True when bidding optimizes towards this goal.");
    // Only written when campaign_id is given, so neither belongs in required.
    ObjectNode campaignGoal =
        McpSchemas.objectArrayProp(
            schema, "campaignGoals", "The named campaign's own conversion goals.");
    McpSchemas.prop(campaignGoal, "category", "string", "Conversion goal category.");
    McpSchemas.prop(campaignGoal, "origin", "string", "Where the conversions come from.");
    McpSchemas.prop(
        campaignGoal, "biddable", "boolean", "True when the campaign optimizes towards this goal.");
    McpSchemas.nullableProp(
        schema,
        "campaignGoalConfigLevel",
        "string",
        "CAMPAIGN when the named campaign carries its own goals, CUSTOMER when it follows the"
            + " account-level ones.");
    McpSchemas.required(schema, "customerId", "dateRange", "conversionActions", "conversionGoals");
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
    // Arguments first: decrypting a token can refresh it against Google, which is
    // wasted work on a call that cannot run.
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
    boolean includeRemoved =
        args.hasNonNull("include_removed") && args.get("include_removed").asBoolean(false);
    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;

    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    List<GoogleConversionActionDto> actions =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listConversionActions(token, customerId, loginCustomerId, includeRemoved));
    GoogleConversionActionQuery query =
        new GoogleConversionActionQuery(preset, since, until, includeRemoved);
    List<GoogleConversionActionStatsDto> stats =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .conversionActionStats(token, customerId, loginCustomerId, query));
    List<GoogleConversionGoalDto> accountGoals =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listCustomerConversionGoals(token, customerId, loginCustomerId));
    GoogleCampaignGoalsDto campaignGoals =
        campaignId == null
            ? null
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .listCampaignConversionGoals(
                            token, customerId, loginCustomerId, campaignId));
    // The goals actually in force for what the caller asked about: a campaign on
    // campaign-specific settings ignores the account goals entirely.
    List<GoogleConversionGoalDto> effectiveGoals =
        campaignGoals != null && campaignGoals.usesCampaignGoals()
            ? campaignGoals.goals()
            : accountGoals;
    Set<String> biddableKeys = new HashSet<>();
    for (GoogleConversionGoalDto goal : effectiveGoals) {
      if (goal.biddable()) {
        biddableKeys.add(goal.key());
      }
    }

    // Name is the only key the two reports share.
    Map<String, GoogleConversionActionStatsDto> statsByName = new HashMap<>();
    for (GoogleConversionActionStatsDto stat : stats) {
      if (stat.actionName() != null) {
        statsByName.put(stat.actionName(), stat);
      }
    }

    String range = preset != null ? preset : since + ".." + until;
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("customerId", customerId);
    structured.put("currency", currency);
    structured.put("dateRange", range);
    ArrayNode arr = structured.putArray("conversionActions");
    putGoals(structured.putArray("conversionGoals"), accountGoals);
    if (campaignGoals != null) {
      putGoals(structured.putArray("campaignGoals"), campaignGoals.goals());
      structured.put("campaignGoalConfigLevel", campaignGoals.goalConfigLevel());
    }
    StringBuilder text =
        new StringBuilder(
            "Conversion tracking for Google Ads account " + customerId + " (" + range + "):\n");
    text.append(goalsLine(effectiveGoals, campaignGoals)).append("\n");

    for (GoogleConversionActionDto action : actions) {
      GoogleConversionActionStatsDto stat = statsByName.get(action.name());
      double conversions = stat != null ? stat.allConversions() : 0;
      long valueCents = stat != null ? stat.allConversionsValueCents() : 0;

      ObjectNode node = arr.addObject();
      node.put("id", action.id());
      node.put("name", action.name());
      node.put("category", action.category());
      node.put("type", action.type());
      node.put("status", action.status());
      node.put("primaryForGoal", action.primaryForGoal());
      node.put("countingType", action.countingType());
      node.put("attributionModel", action.attributionModel());
      node.put(
          "defaultValue",
          action.defaultValueCents() == null ? null : Money.majorUnits(action.defaultValueCents()));
      node.put("defaultCurrencyCode", action.defaultCurrencyCode());
      node.put("alwaysUseDefaultValue", action.alwaysUseDefaultValue());
      node.put("clickThroughLookbackDays", action.clickThroughLookbackDays());
      node.put("viewThroughLookbackDays", action.viewThroughLookbackDays());
      node.put("includeInConversionsMetric", action.includeInConversionsMetric());
      node.put("origin", action.origin());
      node.put("allConversions", conversions);
      node.put("allConversionsValue", Money.majorUnits(valueCents));

      text.append("• ")
          .append(action.name())
          .append(" — ")
          .append(action.category())
          .append(Boolean.TRUE.equals(action.primaryForGoal()) ? ", PRIMARY" : ", secondary")
          .append(", ")
          .append(action.countingType())
          .append(", ")
          .append(conversions)
          .append(" conversions")
          .append(
              valueCents > 0 ? " worth " + Money.display(valueCents, currency) : " with no value")
          .append(flags(action, conversions, biddableKeys))
          .append("\n");
    }
    if (actions.isEmpty()) {
      text.append(
          "(no conversion actions — with nothing to count, Smart Bidding has no signal and every"
              + " conversion-based recommendation is guesswork)");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  private void putGoals(ArrayNode arr, List<GoogleConversionGoalDto> goals) {
    for (GoogleConversionGoalDto goal : goals) {
      ObjectNode node = arr.addObject();
      node.put("category", goal.category());
      node.put("origin", goal.origin());
      node.put("biddable", goal.biddable());
    }
  }

  private String goalsLine(
      List<GoogleConversionGoalDto> effectiveGoals, GoogleCampaignGoalsDto campaignGoals) {
    List<String> biddable = new ArrayList<>();
    for (GoogleConversionGoalDto goal : effectiveGoals) {
      if (goal.biddable()) {
        biddable.add(goal.key());
      }
    }
    String source =
        campaignGoals == null
            ? "Account goals bidding optimizes for"
            : (campaignGoals.usesCampaignGoals()
                ? "Campaign-specific goals bidding optimizes for"
                : "Account goals this campaign inherits");
    return source
        + ": "
        + (biddable.isEmpty()
            ? "none — no conversion bidding strategy can run until one goal is biddable"
            : String.join(", ", biddable));
  }

  // The cases worth a human's attention, spelled out so the model does not have to
  // infer them from the field values.
  private String flags(
      GoogleConversionActionDto action, double conversions, Set<String> biddableKeys) {
    StringBuilder flags = new StringBuilder();
    // The case the goal system makes easy to miss: the action is enabled and primary,
    // but the goal covering it is not biddable, so bidding never sees it.
    if (action.category() != null
        && action.origin() != null
        && !biddableKeys.contains(action.category() + ":" + action.origin())) {
      flags.append(
          " [goal "
              + action.category()
              + ":"
              + action.origin()
              + " is not biddable — bidding and the conversions column ignore this action;"
              + " google_update_campaign_conversion_goals turns it on for a campaign]");
    }
    if (Boolean.TRUE.equals(action.primaryForGoal()) && conversions == 0) {
      flags.append(" [primary but recorded nothing in this range]");
    }
    if (Boolean.TRUE.equals(action.alwaysUseDefaultValue())) {
      flags.append(" [flat value for every conversion — real order values are ignored]");
    }
    if ("MANY_PER_CLICK".equals(action.countingType()) && isPurchaseLike(action)) {
      flags.append(" [counts every occurrence, which inflates purchase counts]");
    }
    return flags.toString();
  }

  private boolean isPurchaseLike(GoogleConversionActionDto action) {
    return "PURCHASE".equals(action.category()) || "SUBMIT_LEAD_FORM".equals(action.category());
  }
}
