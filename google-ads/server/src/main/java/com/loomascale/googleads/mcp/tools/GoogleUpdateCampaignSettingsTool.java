package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleCampaignSettingsUpdate;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// The plain settings of a campaign that no other tool owns: its name and the dates it runs
// between. Small surface, and one genuinely dangerous case inside it.
//
// A campaign whose end date is in the past reads as ENABLED in every report and spends
// nothing, which is one of the most common findings in an audit and had no fix on this
// surface. Clearing that end date resumes real spend — and it does so WITHOUT going through
// google_activate_campaign, whose wasCreatedByUs allowlist and budget check are what
// normally stand between the assistant and live delivery. So that one path re-runs the
// budget guardrail here; see resumesDelivery below.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateCampaignSettingsTool implements AdsTool {

  private static final int MAX_CAMPAIGN_NAME_LENGTH = 128;

  // Arguments that belong to a sibling tool. The repo's campaign edits are deliberately
  // facet-split, so a name like "settings" attracts requests it does not serve; naming the
  // right tool costs the model one turn, and a tool error does not consume quota.
  private static final Map<String, String> FOREIGN_ARGUMENTS =
      Map.of(
          "daily_budget", "Daily budgets live in google_update_budget.",
          "budget", "Daily budgets live in google_update_budget.",
          "status", "Pausing and enabling is google_set_status.",
          "strategy", "Bidding is google_update_campaign_bidding.",
          "max_cpc", "Bidding is google_update_campaign_bidding.",
          "target_cpa", "Bidding is google_update_campaign_bidding.",
          "target_roas", "Bidding is google_update_campaign_bidding.",
          "locations", "Locations and languages are google_update_campaign_targeting.",
          "languages", "Locations and languages are google_update_campaign_targeting.",
          "keywords", "Keywords are google_add_keywords and google_remove_keywords.");

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_campaign_settings";
  }

  @Override
  public String description() {
    return "Rename a Google Ads campaign and change the dates it runs between. Removing an end"
        + " date is the fix for a campaign that looks ENABLED but spends nothing because its end"
        + " date has passed — pass end_date as null to let it run until it is paused. An end date"
        + " in the past is refused, because it would stop a campaign retroactively; use"
        + " google_set_status with PAUSED for that, which is reversible. The start date of a"
        + " campaign that has already started cannot be moved. For daily budgets use"
        + " google_update_budget, for pausing and enabling google_set_status, for bidding"
        + " google_update_campaign_bidding, and for locations and languages"
        + " google_update_campaign_targeting.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign to change.");
    McpSchemas.prop(
        schema,
        "name",
        "string",
        "New campaign name, at most "
            + MAX_CAMPAIGN_NAME_LENGTH
            + " characters. Must be unique among the account's campaigns. Renaming breaks saved"
            + " reports, rules and scripts that match on the old name, so the result reports what"
            + " the previous name was.");
    McpSchemas.prop(
        schema,
        "start_date",
        "string",
        "New start date as YYYY-MM-DD in the serving account's own timezone. The campaign starts"
            + " at the beginning of that day; time of day cannot be set here. Only settable on a"
            + " campaign that has not started yet.");
    McpSchemas.prop(
        schema,
        "end_date",
        "string",
        "New end date as YYYY-MM-DD. The campaign stops at the end of that day. Pass null to"
            + " remove the end date so the campaign runs until it is paused. Must not be in the"
            + " past.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was changed.");
    McpSchemas.prop(schema, "name", "string", "Name now in effect.");
    McpSchemas.stringArrayProp(schema, "changed", "Which fields this call actually wrote.");
    McpSchemas.nullableProp(
        schema, "previousName", "string", "Name the campaign had before this call.");
    McpSchemas.nullableProp(schema, "startDate", "string", "Start date now in effect.");
    McpSchemas.nullableProp(
        schema,
        "endDate",
        "string",
        "End date now in effect. Null means the campaign runs until it is paused.");
    McpSchemas.stringArrayProp(
        schema,
        "warnings",
        "Consequences worth knowing about, such as a paused-by-date campaign becoming live"
            + " again.");
    McpSchemas.required(schema, "campaignId", "name", "changed");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: clearing or extending an end date makes an ENABLED campaign eligible to
    // serve again, so this tool can start real spend; and a rename cannot be undone from the
    // chat without knowing the old name, which is why the result carries previousName.
    // Idempotent: every field is an absolute value, and a name equal to the current one is
    // skipped rather than rewritten.
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
    for (Map.Entry<String, String> foreign : FOREIGN_ARGUMENTS.entrySet()) {
      if (args.has(foreign.getKey())) {
        throw new McpToolException(
            "google_update_campaign_settings does not change "
                + foreign.getKey()
                + ". "
                + foreign.getValue());
      }
    }
    // An explicit null end_date is the request to remove it, so presence and non-nullness
    // mean different things.
    boolean endDateGiven = args.has("end_date");
    boolean clearEndDate = endDateGiven && args.get("end_date").isNull();
    if (!args.hasNonNull("name") && !args.hasNonNull("start_date") && !endDateGiven) {
      throw new McpToolException(
          "Provide name, start_date, end_date, or a combination — there is nothing to change.");
    }

    String requestedName = args.hasNonNull("name") ? args.get("name").asText().trim() : null;
    if (requestedName != null) {
      if (requestedName.isBlank()) {
        throw new McpToolException("name cannot be blank.");
      }
      if (requestedName.length() > MAX_CAMPAIGN_NAME_LENGTH) {
        throw new McpToolException(
            "name must be at most "
                + MAX_CAMPAIGN_NAME_LENGTH
                + " characters (got "
                + requestedName.length()
                + ").");
      }
      // Google rejects control characters with an error that names neither the field nor
      // the character.
      if (requestedName.chars().anyMatch(c -> c < 0x20)) {
        throw new McpToolException("name cannot contain control characters.");
      }
    }
    LocalDate startDate = date(args, "start_date");
    LocalDate endDate = clearEndDate ? null : date(args, "end_date");

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    List<GoogleCampaignDto> campaigns =
        adsService.call(
            connection,
            () -> adsService.client().listCampaigns(token, customerId, loginCustomerId));
    GoogleCampaignDto campaign =
        campaigns.stream()
            .filter(c -> campaignId.equals(c.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new McpToolException(
                        "Campaign "
                            + campaignId
                            + " was not found in account "
                            + customerId
                            + ". Use google_list_campaigns to see campaigns."));

    // The listing is already in hand, so a name collision is refused here rather than
    // arriving as campaignError.DUPLICATE_CAMPAIGN_NAME.
    List<String> changed = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String name = null;
    if (requestedName != null) {
      if (requestedName.equals(campaign.name())) {
        warnings.add("The name was already \"" + requestedName + "\", so it was left alone.");
      } else {
        campaigns.stream()
            .filter(c -> !campaignId.equals(c.id()))
            .filter(c -> requestedName.equalsIgnoreCase(c.name()))
            .findFirst()
            .ifPresent(
                clash -> {
                  throw new McpToolException(
                      "Campaign "
                          + clash.id()
                          + " is already called \""
                          + clash.name()
                          + "\", and Google Ads requires campaign names to be unique in an"
                          + " account. Pick a different name.");
                });
        name = requestedName;
        changed.add("name");
      }
    }

    LocalDate today = LocalDate.now();
    LocalDate currentStart = parseOrNull(campaign.startDate());
    LocalDate currentEnd = parseOrNull(campaign.endDate());
    if (startDate != null) {
      if (currentStart != null && !startDate.equals(currentStart) && !currentStart.isAfter(today)) {
        throw new McpToolException(
            "Campaign \""
                + campaign.name()
                + "\" started on "
                + campaign.startDate()
                + ", and Google does not allow the start date of a campaign that has already"
                + " started to be moved. Change the end date instead, or build a new campaign.");
      }
      changed.add("start_date");
    }
    if (endDate != null) {
      if (endDate.isBefore(today)) {
        throw new McpToolException(
            "An end date of "
                + endDate
                + " is in the past, which would stop campaign \""
                + campaign.name()
                + "\" retroactively — Google reports such a campaign as ended and it serves"
                + " nothing. To stop it now use google_set_status with PAUSED, which is"
                + " reversible.");
      }
      changed.add("end_date");
    }
    if (clearEndDate) {
      changed.add("end_date");
    }

    LocalDate effectiveStart = startDate != null ? startDate : currentStart;
    LocalDate effectiveEnd = clearEndDate ? null : (endDate != null ? endDate : currentEnd);
    if (effectiveStart != null && effectiveEnd != null && effectiveEnd.isBefore(effectiveStart)) {
      throw new McpToolException(
          "An end date of "
              + effectiveEnd
              + " is before the campaign's start date of "
              + effectiveStart
              + ". Pass both dates if you mean to move the whole schedule.");
    }

    if (changed.isEmpty()) {
      log.debug("google_update_campaign_settings user={} nothing to change", userId);
      return ToolResult.ok(
          "Campaign \""
              + campaign.name()
              + "\" already has those settings, so nothing was changed."
              + (warnings.isEmpty() ? "" : " " + String.join(" ", warnings)),
          structured(campaignId, campaign, campaign.name(), changed, warnings));
    }

    // Clearing or extending a past end date on an ENABLED campaign starts real spend, and it
    // bypasses google_activate_campaign's allowlist and budget check entirely, so the budget
    // cap is enforced here instead.
    boolean resumesDelivery =
        "ENABLED".equals(campaign.status())
            && currentEnd != null
            && currentEnd.isBefore(today)
            && (clearEndDate || (endDate != null && !endDate.isBefore(today)));
    if (resumesDelivery) {
      if (campaign.dailyBudgetCents() == null || campaign.dailyBudgetCents() <= 0) {
        throw new McpToolException(
            "Campaign \""
                + campaign.name()
                + "\" ended on "
                + campaign.endDate()
                + " and this change would make it eligible to serve again, but its daily budget"
                + " cannot be read (usually a shared budget), so it cannot be checked"
                + " against your daily cap. Set a campaign budget with google_update_budget"
                + " first.");
      }
      guardrail.enforce(connection, campaign.dailyBudgetCents(), currency);
      warnings.add(
          "Campaign \""
              + campaign.name()
              + "\" was ENABLED and had ended on "
              + campaign.endDate()
              + "; with the end date "
              + (clearEndDate ? "removed" : "moved to " + endDate)
              + " it is eligible again and will start spending up to "
              + Money.display(campaign.dailyBudgetCents(), currency)
              + " a day immediately.");
    }

    String argsSummary =
        "campaign="
            + campaignId
            + ";name="
            + name
            + ";startDate="
            + startDate
            + ";endDate="
            + (clearEndDate ? "cleared" : String.valueOf(endDate))
            + ";resumes="
            + resumesDelivery;
    log.debug("google_update_campaign_settings user={} {}", userId, argsSummary);

    String writtenName = name;
    LocalDate writtenStart = startDate;
    LocalDate writtenEnd = endDate;
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateCampaignSettings(
                    token,
                    customerId,
                    loginCustomerId,
                    campaignId,
                    new GoogleCampaignSettingsUpdate(
                        writtenName,
                        writtenStart == null ? null : writtenStart.toString(),
                        writtenEnd == null ? null : writtenEnd.toString(),
                        clearEndDate));
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_campaign_settings</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaign.name()
              + " ("
              + campaignId
              + ")\nName: "
              + (writtenName == null ? "unchanged" : writtenName)
              + "\nStart: "
              + (writtenStart == null ? "unchanged" : writtenStart)
              + "\nEnd: "
              + (clearEndDate ? "removed" : (writtenEnd == null ? "unchanged" : writtenEnd))
              + (resumesDelivery ? "\nRESUMES DELIVERY" : ""));

      String effectiveName = writtenName != null ? writtenName : campaign.name();
      return ToolResult.ok(
          text(campaign, effectiveName, effectiveStart, effectiveEnd, clearEndDate, warnings),
          structured(campaignId, campaign, effectiveName, changed, warnings));
    } catch (RuntimeException e) {
      audit.record(
          userId, name(), WriteKind.UPDATE, argsSummary, campaignId, false, e.getMessage());
      throw e;
    }
  }

  // The regex alone accepts 2026-02-30, so the value is parsed as well as matched.
  private LocalDate date(JsonNode args, String field) {
    if (!args.hasNonNull(field)) {
      return null;
    }
    String value = args.get(field).asText("").trim();
    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
      throw new McpToolException(
          field + " must be a date in YYYY-MM-DD form (got \"" + value + "\").");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new McpToolException(field + " is not a real date: \"" + value + "\".");
    }
  }

  private LocalDate parseOrNull(String value) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private ObjectNode structured(
      String campaignId,
      GoogleCampaignDto campaign,
      String effectiveName,
      List<String> changed,
      List<String> warnings) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("campaignId", campaignId);
    structured.put("name", effectiveName);
    changed.forEach(structured.putArray("changed")::add);
    structured.put("previousName", campaign.name());
    structured.put("startDate", campaign.startDate());
    structured.put("endDate", campaign.endDate());
    warnings.forEach(structured.putArray("warnings")::add);
    return structured;
  }

  private String text(
      GoogleCampaignDto campaign,
      String effectiveName,
      LocalDate effectiveStart,
      LocalDate effectiveEnd,
      boolean clearEndDate,
      List<String> warnings) {
    StringBuilder text = new StringBuilder();
    text.append("Campaign ").append(effectiveName).append(" (").append(campaign.id()).append(")");
    if (!effectiveName.equals(campaign.name())) {
      text.append(", renamed from \"").append(campaign.name()).append("\"");
    }
    text.append(" runs ")
        .append(effectiveStart == null ? "from its existing start date" : "from " + effectiveStart)
        .append(
            clearEndDate || effectiveEnd == null
                ? " with no end date — it will keep serving until it is paused."
                : " until " + effectiveEnd + ".")
        .append("\n");
    warnings.forEach(warning -> text.append(warning).append("\n"));
    return text.toString();
  }
}
