package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleCampaignGoalsDto;
import com.loomascale.googleads.client.dto.GoogleConversionGoalDto;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// What a campaign optimizes towards — the "Use campaign-specific goal settings" screen.
// Google groups conversion actions into goals by (category, origin) and only a biddable
// goal feeds Smart Bidding, so an action can be enabled and primary and still be
// invisible to the campaign. Until this tool existed there was no way to say "optimize
// for sign-ups only" without leaving the assistant for the Google Ads UI.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateCampaignConversionGoalsTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_campaign_conversion_goals";
  }

  @Override
  public String description() {
    return "Choose which conversion goals a Google Ads campaign optimizes for, which is what the"
        + " Google Ads UI calls campaign-specific goal settings. Goals are groups of conversion"
        + " actions by category and origin — pass the categories the campaign should bid towards"
        + " (for example SIGNUP) and every other goal is switched off for that campaign alone."
        + " Use this before switching a campaign to MAXIMIZE_CONVERSIONS with"
        + " google_update_campaign_bidding, or when a campaign optimizes for the wrong events."
        + " Read the current goals and the categories available with"
        + " google_list_conversion_actions. Pass use_account_goals to hand the campaign back to the"
        + " account-level goals.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign whose conversion goals to set.");
    McpSchemas.stringArrayProp(
        schema,
        "biddable_categories",
        "Conversion goal categories this campaign should optimize for, e.g. [\"SIGNUP\"]. Every"
            + " goal not listed is switched off for this campaign. An entry may be a bare category,"
            + " which covers every origin the campaign has for it, or CATEGORY:ORIGIN to pick one"
            + " origin, e.g. SIGNUP:WEBSITE.");
    McpSchemas.prop(
        schema,
        "use_account_goals",
        "boolean",
        "Drop the campaign-specific goals and go back to the account-level goals. Cannot be"
            + " combined with biddable_categories.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was updated.");
    McpSchemas.prop(
        schema,
        "goalConfigLevel",
        "string",
        "CAMPAIGN when the campaign now carries its own goals, CUSTOMER when it follows the"
            + " account-level goals.");
    ObjectNode goal =
        McpSchemas.objectArrayProp(
            schema, "goals", "Every goal of this campaign after the change.");
    McpSchemas.prop(goal, "category", "string", "Conversion goal category, e.g. SIGNUP.");
    McpSchemas.prop(goal, "origin", "string", "Where the conversions come from, e.g. WEBSITE.");
    McpSchemas.prop(
        goal, "biddable", "boolean", "True when the campaign optimizes towards this goal.");
    McpSchemas.required(schema, "campaignId", "goalConfigLevel", "goals");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: what a campaign counts as a conversion drives Smart Bidding, so a
    // wrong goal spends the budget on the wrong events. Idempotent: it writes the whole
    // set of goals, so a repeat call lands on the same state.
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
    boolean useAccountGoals =
        args.hasNonNull("use_account_goals") && args.get("use_account_goals").asBoolean(false);
    List<String> requested = requestedCategories(args);
    if (useAccountGoals && !requested.isEmpty()) {
      throw new McpToolException(
          "use_account_goals hands the campaign back to the account goals, so it cannot be combined"
              + " with biddable_categories. Pass one of them.");
    }
    if (!useAccountGoals && requested.isEmpty()) {
      throw new McpToolException(
          "Provide biddable_categories with at least one category, or use_account_goals. A campaign"
              + " with no biddable goal cannot optimize for conversions at all.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleCampaignDto campaign =
        support.requireCampaign(connection, token, customerId, loginCustomerId, campaignId);
    GoogleCampaignGoalsDto before =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listCampaignConversionGoals(token, customerId, loginCustomerId, campaignId));
    if (settableGoals(before.goals()).isEmpty()) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" has no conversion goals to set. Google creates them from the account's"
              + " conversion actions, so check google_list_conversion_actions — an account with no"
              + " conversion tracking has nothing to optimize towards.");
    }

    return useAccountGoals
        ? revertToAccountGoals(userId, connection, token, customerId, loginCustomerId, campaign)
        : setCampaignGoals(
            userId, connection, token, customerId, loginCustomerId, campaign, before, requested);
  }

  private ToolResult setCampaignGoals(
      String userId,
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      GoogleCampaignDto campaign,
      GoogleCampaignGoalsDto before,
      List<String> requested) {
    List<GoogleConversionGoalDto> settable = settableGoals(before.goals());
    Set<String> selected = resolveSelection(campaign, settable, requested);
    // Absolute set, not a patch: every settable goal is written so the result is the
    // whole configuration and a repeat call is a no-op. A goal Google cannot address
    // is left exactly as it was — it has no resource name to mutate.
    Map<String, Boolean> biddableByKey = new LinkedHashMap<>();
    List<GoogleConversionGoalDto> after = new ArrayList<>();
    for (GoogleConversionGoalDto goal : before.goals()) {
      if (!goal.addressable()) {
        after.add(goal);
        continue;
      }
      boolean biddable = selected.contains(goal.key());
      biddableByKey.put(goal.key(), biddable);
      after.add(new GoogleConversionGoalDto(goal.category(), goal.origin(), biddable));
    }

    String argsSummary = "campaign=" + campaign.id() + ";biddable=" + String.join(",", selected);
    log.debug("google_update_campaign_conversion_goals user={} {}", userId, argsSummary);
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateCampaignConversionGoals(
                    token, customerId, loginCustomerId, campaign.id(), biddableByKey);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaign.id(), true, null);
      audit.alert(
          "<b>MCP Ads: google_update_campaign_conversion_goals</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaign.name()
              + " ("
              + campaign.id()
              + ")\nOptimizing for: "
              + String.join(", ", selected));
      // Updating any campaign goal moves the campaign off the account goals by itself.
      return ToolResult.ok(
          "Campaign \""
              + campaign.name()
              + "\" now optimizes for "
              + String.join(", ", selected)
              + " and ignores "
              + describeDisabled(after)
              + ". It uses campaign-specific goal settings from now on."
              + unaddressableNote(before.goals())
              + " Switch it to MAXIMIZE_CONVERSIONS with google_update_campaign_bidding for the"
              + " goals to drive delivery.",
          structured(campaign.id(), GoogleCampaignGoalsDto.LEVEL_CAMPAIGN, after));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaign.id(), false, e.getMessage());
      throw e;
    }
  }

  private ToolResult revertToAccountGoals(
      String userId,
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      GoogleCampaignDto campaign) {
    String argsSummary = "campaign=" + campaign.id() + ";level=CUSTOMER";
    log.debug("google_update_campaign_conversion_goals user={} {}", userId, argsSummary);
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .setCampaignGoalConfigLevel(
                    token,
                    customerId,
                    loginCustomerId,
                    campaign.id(),
                    GoogleCampaignGoalsDto.LEVEL_CUSTOMER);
            return null;
          });
      List<GoogleConversionGoalDto> accountGoals =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .listCustomerConversionGoals(token, customerId, loginCustomerId));
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaign.id(), true, null);
      audit.alert(
          "<b>MCP Ads: google_update_campaign_conversion_goals</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaign.name()
              + " ("
              + campaign.id()
              + ")\nBack to account-level goals");
      return ToolResult.ok(
          "Campaign \""
              + campaign.name()
              + "\" now follows the account-level conversion goals: "
              + describeBiddable(accountGoals)
              + ".",
          structured(campaign.id(), GoogleCampaignGoalsDto.LEVEL_CUSTOMER, accountGoals));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaign.id(), false, e.getMessage());
      throw e;
    }
  }

  // The goals this tool can actually write. Google materializes a row per enum value,
  // including the UNKNOWN sentinel it uses for a category this API version cannot name;
  // that row has no valid resource name, and leaving it in the mutate fails every other
  // operation with it (BAD_RESOURCE_ID on the whole request).
  private List<GoogleConversionGoalDto> settableGoals(List<GoogleConversionGoalDto> goals) {
    List<GoogleConversionGoalDto> settable = new ArrayList<>();
    for (GoogleConversionGoalDto goal : goals) {
      if (goal.addressable()) {
        settable.add(goal);
      }
    }
    return settable;
  }

  // Only worth saying when such a goal is biddable: the campaign keeps optimizing
  // towards it and no argument to this tool can turn it off.
  private String unaddressableNote(List<GoogleConversionGoalDto> before) {
    List<String> stuck = new ArrayList<>();
    for (GoogleConversionGoalDto goal : before) {
      if (!goal.addressable() && goal.biddable()) {
        stuck.add(goal.key());
      }
    }
    return stuck.isEmpty()
        ? ""
        : " Google also reports the goal "
            + String.join(", ", stuck)
            + " as biddable but gives it no resource name, so it could not be changed here —"
            + " turn it off in the Google Ads UI if it should not count.";
  }

  // Turns the CATEGORY / CATEGORY:ORIGIN entries into the goal keys the campaign
  // actually has. An entry matching nothing is a typo or a category the account does not
  // track, and silently ignoring it would switch the campaign to a smaller goal set than
  // the caller asked for.
  private Set<String> resolveSelection(
      GoogleCampaignDto campaign, List<GoogleConversionGoalDto> goals, List<String> requested) {
    Set<String> selected = new LinkedHashSet<>();
    for (String entry : requested) {
      String normalized = entry.trim().toUpperCase().replace('~', ':');
      List<String> matches = new ArrayList<>();
      for (GoogleConversionGoalDto goal : goals) {
        if (normalized.equals(goal.category()) || normalized.equals(goal.key())) {
          matches.add(goal.key());
        }
      }
      if (matches.isEmpty()) {
        throw new McpToolException(
            "Campaign \""
                + campaign.name()
                + "\" has no conversion goal "
                + normalized
                + ". Its goals are: "
                + describeAll(goals)
                + ".");
      }
      selected.addAll(matches);
    }
    return selected;
  }

  private List<String> requestedCategories(JsonNode args) {
    List<String> categories = new ArrayList<>();
    JsonNode node = args.path("biddable_categories");
    if (node.isArray()) {
      for (JsonNode entry : node) {
        if (entry.isTextual() && !entry.asText().isBlank()) {
          categories.add(entry.asText());
        }
      }
    }
    return categories;
  }

  private ObjectNode structured(
      String campaignId, String goalConfigLevel, List<GoogleConversionGoalDto> goals) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("campaignId", campaignId);
    structured.put("goalConfigLevel", goalConfigLevel);
    ArrayNode arr = structured.putArray("goals");
    for (GoogleConversionGoalDto goal : goals) {
      ObjectNode node = arr.addObject();
      node.put("category", goal.category());
      node.put("origin", goal.origin());
      node.put("biddable", goal.biddable());
    }
    return structured;
  }

  private String describeAll(List<GoogleConversionGoalDto> goals) {
    List<String> keys = new ArrayList<>();
    for (GoogleConversionGoalDto goal : goals) {
      keys.add(goal.key());
    }
    return String.join(", ", keys);
  }

  private String describeDisabled(List<GoogleConversionGoalDto> goals) {
    List<String> keys = new ArrayList<>();
    for (GoogleConversionGoalDto goal : goals) {
      if (!goal.biddable()) {
        keys.add(goal.key());
      }
    }
    return keys.isEmpty() ? "nothing else" : String.join(", ", keys);
  }

  private String describeBiddable(List<GoogleConversionGoalDto> goals) {
    List<String> keys = new ArrayList<>();
    for (GoogleConversionGoalDto goal : goals) {
      if (goal.biddable()) {
        keys.add(goal.key());
      }
    }
    return keys.isEmpty() ? "none of them are biddable" : String.join(", ", keys);
  }
}
