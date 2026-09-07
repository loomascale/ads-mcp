package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleConversionActionDto;
import com.loomascale.googleads.client.dto.GoogleConversionActionUpdateSpec;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Fixes a conversion action's own settings. google_list_conversion_actions has always been
// able to say "this purchase action counts every occurrence, which inflates the number",
// and google_update_campaign_conversion_goals can pick which categories a campaign bids
// towards — but nothing could change the action itself, so every finding of the audit
// ended in "open Google Ads and change it there".
//
// This is the resource that decides what the account records. The campaign goals decide
// which recorded events a campaign optimizes for; they are not a substitute for counting
// a purchase correctly in the first place.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateConversionActionTool implements AdsTool {

  private static final List<String> COUNTING_TYPES = List.of("ONE_PER_CLICK", "MANY_PER_CLICK");

  // ENABLED and HIDDEN only. REMOVED is deliberately absent: Google cannot undo it through
  // the API, and a removed action takes its history out of the conversions column.
  private static final List<String> STATUSES = List.of("ENABLED", "HIDDEN");

  private static final int MAX_CLICK_LOOKBACK_DAYS = 90;
  private static final int MAX_VIEW_LOOKBACK_DAYS = 30;

  // A conversion action that Google Ads only imports is owned by the system that produced
  // it, and Google refuses most edits to it with IMMUTABLE_FIELD — only its name, category,
  // status, primary flag and value settings may be changed here. Matched by type prefix
  // because the type is the only field that names the source; origin reads WEBSITE for a
  // GA4 web key event exactly as it does for a Google Ads tag.
  private static final List<String> IMPORTED_TYPE_PREFIXES =
      List.of(
          "GOOGLE_ANALYTICS_4_", "UNIVERSAL_ANALYTICS_", "FIREBASE_", "THIRD_PARTY_APP_ANALYTICS_");

  // The settings an imported action refuses. Everything else this tool writes is on
  // Google's permitted list for imports.
  private static final List<String> IMPORT_LOCKED_FIELDS =
      List.of("counting_type", "click_through_lookback_days", "view_through_lookback_days");

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_conversion_action";
  }

  @Override
  public String description() {
    return "Change how a Google Ads conversion action counts and values conversions: its counting"
        + " type (ONE_PER_CLICK counts one conversion per click, MANY_PER_CLICK counts every"
        + " occurrence), its category, whether it is primary and therefore feeds Smart Bidding"
        + " and the conversions column, its default value and currency, and its click-through and"
        + " view-through lookback windows. Use it to fix what google_list_conversion_actions"
        + " reports — a purchase action counting every occurrence, a lead action inflating the"
        + " number, a flat default value on an action that should send real order values. Read"
        + " the action first with google_list_conversion_actions. Only the settings you pass"
        + " change; the rest are left alone. Note that Google Ads only imports an action whose"
        + " type starts with GOOGLE_ANALYTICS_4_, UNIVERSAL_ANALYTICS_, FIREBASE_ or"
        + " THIRD_PARTY_APP_ANALYTICS_, and refuses to change its counting type or its lookback"
        + " windows through the API — those live in the system that produces the conversion, so"
        + " for a Google Analytics key event the counting method is set in Analytics. This"
        + " changes counting from now on and does not restate past conversions, and Smart Bidding"
        + " takes a week or two to settle afterwards. To choose which conversions a single"
        + " campaign optimizes for, use google_update_campaign_conversion_goals instead. Deleting"
        + " a conversion action is not offered here because it cannot be undone.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "conversion_action_id",
        "string",
        "Conversion action to change, as reported by google_list_conversion_actions.");
    ObjectNode countingType =
        McpSchemas.prop(
            schema,
            "counting_type",
            "string",
            "How repeat events after one click are counted. ONE_PER_CLICK (the Google Ads UI calls"
                + " it \"One\") suits leads, sign-ups and contacts, where the same person filling"
                + " the form twice is still one lead. MANY_PER_CLICK (\"Every\") suits purchases,"
                + " where two orders are two conversions.");
    McpSchemas.enumValues(countingType, COUNTING_TYPES);
    McpSchemas.prop(
        schema,
        "primary_for_goal",
        "boolean",
        "True makes the action primary: it feeds Smart Bidding and the main conversions column."
            + " False makes it secondary — still recorded and reportable, but not bid towards.");
    ObjectNode status =
        McpSchemas.prop(
            schema,
            "status",
            "string",
            "ENABLED records conversions for this action. HIDDEN stops it being recorded and takes"
                + " it out of the interface without deleting it.");
    McpSchemas.enumValues(status, STATUSES);
    McpSchemas.prop(
        schema,
        "default_value",
        "number",
        McpSchemas.moneyInputDescription(
            "Value a conversion of this action is worth when the tag sends no value of its own."));
    McpSchemas.prop(
        schema,
        "default_currency_code",
        "string",
        "ISO currency code the default value is denominated in, e.g. USD. Defaults to the"
            + " account currency when the action has none.");
    McpSchemas.prop(
        schema,
        "always_use_default_value",
        "boolean",
        "True makes every conversion worth the default value, ignoring the value the tag sends —"
            + " which quietly breaks value-based bidding on a purchase action. False lets real"
            + " transaction values through.");
    McpSchemas.prop(
        schema,
        "click_through_lookback_days",
        "integer",
        "How many days after a click a conversion still counts, 1-"
            + MAX_CLICK_LOOKBACK_DAYS
            + ".");
    McpSchemas.prop(
        schema,
        "view_through_lookback_days",
        "integer",
        "How many days after an impression without a click a conversion still counts, 1-"
            + MAX_VIEW_LOOKBACK_DAYS
            + ".");
    McpSchemas.prop(
        schema,
        "category",
        "string",
        "Conversion category this action reports under, e.g. PURCHASE, SUBMIT_LEAD_FORM, SIGNUP,"
            + " CONTACT. Categories are how conversion goals group actions, so changing it moves"
            + " the action into a different goal. Editable even on an action imported from"
            + " Google Analytics.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "conversion_action_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "conversionActionId", "string", "Action that was changed.");
    McpSchemas.prop(schema, "name", "string", "Its name in Google Ads.");
    McpSchemas.stringArrayProp(
        schema, "changed", "Settings this call actually wrote, by their Google field name.");
    McpSchemas.nullableProp(
        schema, "category", "string", "Conversion category, e.g. PURCHASE or SUBMIT_LEAD_FORM.");
    McpSchemas.nullableProp(
        schema, "countingType", "string", "Counting type now in effect for this action.");
    McpSchemas.nullableProp(
        schema, "previousCountingType", "string", "Counting type it had before this call.");
    McpSchemas.nullableProp(
        schema, "primaryForGoal", "boolean", "Whether the action is primary now.");
    McpSchemas.nullableProp(schema, "status", "string", "Its status now.");
    McpSchemas.moneyProp(schema, "defaultValue", "Default value now in effect, if it has one.");
    McpSchemas.nullableProp(
        schema, "defaultCurrencyCode", "string", "Currency the default value is in.");
    McpSchemas.nullableProp(
        schema,
        "alwaysUseDefaultValue",
        "boolean",
        "Whether every conversion is now forced to the default value.");
    McpSchemas.nullableProp(
        schema, "clickThroughLookbackDays", "integer", "Click-through lookback window now.");
    McpSchemas.nullableProp(
        schema, "viewThroughLookbackDays", "integer", "View-through lookback window now.");
    McpSchemas.nullableProp(
        schema,
        "type",
        "string",
        "What kind of conversion action this is, e.g. WEBPAGE for a Google Ads tag or"
            + " GOOGLE_ANALYTICS_4_CUSTOM for a key event imported from Analytics. The type"
            + " decides which settings Google lets the API change.");
    McpSchemas.required(schema, "conversionActionId", "name", "changed");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: what the account counts as a conversion drives Smart Bidding, so a wrong
    // counting type or a demoted primary action spends the budget on the wrong events, and
    // this tool cannot restore the reporting the previous setting would have produced.
    // Idempotent: it writes absolute values, so a repeat call lands on the same state.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("conversion_action_id")) {
      throw new McpToolException("conversion_action_id is required.");
    }
    String actionId = args.get("conversion_action_id").asText();

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleConversionActionDto current =
        requireAction(connection, token, customerId, loginCustomerId, actionId);

    requireEditableSettings(args, current);

    GoogleConversionActionUpdateSpec spec = parseSpec(args, current);
    if (spec.isEmpty()) {
      throw new McpToolException(
          "Provide counting_type, primary_for_goal, status, category, default_value,"
              + " default_currency_code, always_use_default_value, click_through_lookback_days,"
              + " view_through_lookback_days, or any combination — there is nothing to change.");
    }

    String argsSummary = "action=" + actionId + ";" + changeSummary(spec);
    log.debug("google_update_conversion_action user={} {}", userId, argsSummary);
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateConversionAction(token, customerId, loginCustomerId, actionId, spec);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, actionId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_conversion_action</b>\nUser: "
              + userId
              + "\nAction: "
              + current.name()
              + " ("
              + actionId
              + ", "
              + current.category()
              + ")\nChanged: "
              + changeSummary(spec));

      return ToolResult.ok(text(current, spec), structured(actionId, current, spec));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, actionId, false, e.getMessage());
      throw immutableFieldHint(e, current);
    }
  }

  // The type check above catches the sources known today, but Google locks settings per
  // conversion action type and the list grows, so a refusal can still arrive from the API.
  // Raw, it reads "The field attempted to be mutated is immutable.
  // [operations[0].update.counting_type]" — true and useless. This turns it back into the
  // sentence the type check would have produced.
  private RuntimeException immutableFieldHint(
      RuntimeException e, GoogleConversionActionDto current) {
    String message = e.getMessage();
    if (message == null
        || !(message.contains("immutable")
            || message.contains("IMMUTABLE_FIELD")
            || message.contains("MUTATE_NOT_ALLOWED"))) {
      return e;
    }
    return new McpToolException(
        "Google refused to change that setting on \""
            + current.name()
            + "\" ("
            + current.type()
            + "): it is fixed for this kind of conversion action. "
            + sourceAdvice(current.type() == null ? "" : current.type())
            + " Google says: "
            + message);
  }

  // An imported conversion action is a mirror of something Google Ads does not own, and
  // Google rejects an edit to the settings the source controls — one live attempt to move a
  // GA4 key event from Every to One came back "The field attempted to be mutated is
  // immutable. [operations[0].update.counting_type]". Refusing here costs the user nothing;
  // reaching Google costs a task and returns an error naming a field, not a way forward.
  private void requireEditableSettings(JsonNode args, GoogleConversionActionDto current) {
    if (!isImported(current.type())) {
      return;
    }
    List<String> locked = IMPORT_LOCKED_FIELDS.stream().filter(args::hasNonNull).toList();
    if (locked.isEmpty()) {
      return;
    }
    throw new McpToolException(
        "\""
            + current.name()
            + "\" is a "
            + current.type()
            + " conversion action — Google Ads only imports it, so Google refuses to change "
            + String.join(" or ", locked)
            + " through the API. "
            + sourceAdvice(current.type())
            + " What can be changed here on an imported action: category, primary_for_goal,"
            + " status, default_value, default_currency_code and always_use_default_value.");
  }

  private boolean isImported(String type) {
    return type != null && IMPORTED_TYPE_PREFIXES.stream().anyMatch(type::startsWith);
  }

  private String sourceAdvice(String type) {
    if (type.startsWith("GOOGLE_ANALYTICS_4_")) {
      return "Counting for a key event lives in Google Analytics: Admin → Key events → the"
          + " counting method column, where \"Once per event\" is what Google Ads reports as"
          + " MANY_PER_CLICK and \"Once per session\" is the nearest thing to ONE_PER_CLICK."
          + " Changing it there flows back into Google Ads. The alternative is a Google Ads tag"
          + " conversion of your own, whose counting this tool can set.";
    }
    if (type.startsWith("UNIVERSAL_ANALYTICS_")) {
      return "It comes from a Universal Analytics goal, which Google Ads cannot edit at all —"
          + " replace it with a Google Analytics 4 key event or a Google Ads tag conversion.";
    }
    return "Change it in the system that produces the conversion, or replace it with a Google"
        + " Ads tag conversion of your own.";
  }

  // Looking the action up in the selected account is also the ownership check: an id from
  // another account is simply absent, and nothing is mutated.
  private GoogleConversionActionDto requireAction(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String actionId) {
    return adsService
        .call(
            connection,
            () ->
                adsService
                    .client()
                    .listConversionActions(token, customerId, loginCustomerId, false))
        .stream()
        .filter(action -> actionId.equals(action.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new McpToolException(
                    "Conversion action "
                        + actionId
                        + " was not found in account "
                        + customerId
                        + ". Use google_list_conversion_actions to see the ids."));
  }

  private GoogleConversionActionUpdateSpec parseSpec(
      JsonNode args, GoogleConversionActionDto current) {
    String countingType = enumArg(args, "counting_type", COUNTING_TYPES);
    String status = enumArg(args, "status", STATUSES);
    Long defaultValueCents = null;
    if (args.hasNonNull("default_value")) {
      double value = args.get("default_value").asDouble();
      if (value < 0) {
        throw new McpToolException("default_value cannot be negative.");
      }
      defaultValueCents = Math.round(value * 100);
    }
    Boolean alwaysUseDefaultValue =
        args.hasNonNull("always_use_default_value")
            ? args.get("always_use_default_value").asBoolean()
            : null;
    // Forcing every conversion to a default value that does not exist would leave every
    // conversion worth nothing, which Google accepts and value-based bidding cannot use.
    if (Boolean.TRUE.equals(alwaysUseDefaultValue)
        && defaultValueCents == null
        && (current.defaultValueCents() == null || current.defaultValueCents() == 0)) {
      throw new McpToolException(
          "always_use_default_value true needs a value to use: pass default_value as well, or the"
              + " action would count every conversion as worth nothing.");
    }
    return new GoogleConversionActionUpdateSpec(
        countingType,
        args.hasNonNull("primary_for_goal") ? args.get("primary_for_goal").asBoolean() : null,
        status,
        defaultValueCents,
        args.hasNonNull("default_currency_code")
            ? args.get("default_currency_code").asText().trim().toUpperCase()
            : null,
        alwaysUseDefaultValue,
        lookback(args, "click_through_lookback_days", MAX_CLICK_LOOKBACK_DAYS),
        lookback(args, "view_through_lookback_days", MAX_VIEW_LOOKBACK_DAYS),
        args.hasNonNull("category") ? args.get("category").asText().trim().toUpperCase() : null);
  }

  private String enumArg(JsonNode args, String field, List<String> allowed) {
    if (!args.hasNonNull(field)) {
      return null;
    }
    String value = args.get(field).asText().trim().toUpperCase();
    if (!allowed.contains(value)) {
      throw new McpToolException(
          field + " must be one of: " + String.join(", ", allowed) + " (got " + value + ").");
    }
    return value;
  }

  private Integer lookback(JsonNode args, String field, int max) {
    if (!args.hasNonNull(field)) {
      return null;
    }
    int days = args.get(field).asInt();
    if (days < 1 || days > max) {
      throw new McpToolException(field + " must be between 1 and " + max + " days.");
    }
    return days;
  }

  // The settings this call writes, as "name=value" pairs in a stable order. changedFields()
  // is the same list without the values, which is what the structured result carries.
  private List<String> changes(GoogleConversionActionUpdateSpec spec) {
    List<String> parts = new ArrayList<>();
    if (spec.countingType() != null) {
      parts.add("countingType=" + spec.countingType());
    }
    if (spec.primaryForGoal() != null) {
      parts.add("primaryForGoal=" + spec.primaryForGoal());
    }
    if (spec.status() != null) {
      parts.add("status=" + spec.status());
    }
    if (spec.defaultValueCents() != null) {
      parts.add("defaultValue=" + spec.defaultValueCents() / 100.0);
    }
    if (spec.defaultCurrencyCode() != null) {
      parts.add("defaultCurrencyCode=" + spec.defaultCurrencyCode());
    }
    if (spec.alwaysUseDefaultValue() != null) {
      parts.add("alwaysUseDefaultValue=" + spec.alwaysUseDefaultValue());
    }
    if (spec.clickThroughLookbackDays() != null) {
      parts.add("clickThroughLookbackDays=" + spec.clickThroughLookbackDays());
    }
    if (spec.viewThroughLookbackDays() != null) {
      parts.add("viewThroughLookbackDays=" + spec.viewThroughLookbackDays());
    }
    if (spec.category() != null) {
      parts.add("category=" + spec.category());
    }
    return parts;
  }

  private String changeSummary(GoogleConversionActionUpdateSpec spec) {
    return String.join(";", changes(spec));
  }

  private List<String> changedFields(GoogleConversionActionUpdateSpec spec) {
    return changes(spec).stream().map(change -> change.substring(0, change.indexOf('='))).toList();
  }

  private ObjectNode structured(
      String actionId, GoogleConversionActionDto current, GoogleConversionActionUpdateSpec spec) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("conversionActionId", actionId);
    structured.put("name", current.name());
    structured.put("category", spec.category() != null ? spec.category() : current.category());
    ArrayNode changed = structured.putArray("changed");
    changedFields(spec).forEach(changed::add);
    structured.put(
        "countingType", spec.countingType() != null ? spec.countingType() : current.countingType());
    structured.put("previousCountingType", current.countingType());
    structured.put(
        "primaryForGoal",
        spec.primaryForGoal() != null ? spec.primaryForGoal() : current.primaryForGoal());
    structured.put("status", spec.status() != null ? spec.status() : current.status());
    Long valueCents =
        spec.defaultValueCents() != null ? spec.defaultValueCents() : current.defaultValueCents();
    if (valueCents == null) {
      structured.putNull("defaultValue");
    } else {
      structured.put("defaultValue", valueCents / 100.0);
    }
    structured.put(
        "defaultCurrencyCode",
        spec.defaultCurrencyCode() != null
            ? spec.defaultCurrencyCode()
            : current.defaultCurrencyCode());
    structured.put(
        "alwaysUseDefaultValue",
        spec.alwaysUseDefaultValue() != null
            ? spec.alwaysUseDefaultValue()
            : current.alwaysUseDefaultValue());
    structured.put(
        "clickThroughLookbackDays",
        spec.clickThroughLookbackDays() != null
            ? spec.clickThroughLookbackDays()
            : current.clickThroughLookbackDays());
    structured.put(
        "viewThroughLookbackDays",
        spec.viewThroughLookbackDays() != null
            ? spec.viewThroughLookbackDays()
            : current.viewThroughLookbackDays());
    structured.put("type", current.type());
    return structured;
  }

  private String text(GoogleConversionActionDto current, GoogleConversionActionUpdateSpec spec) {
    StringBuilder text =
        new StringBuilder(
            "Conversion action \"" + current.name() + "\" (" + current.id() + ") updated: ");
    text.append(changeSummary(spec).replace(";", ", ")).append(".");
    if (spec.countingType() != null && !spec.countingType().equals(current.countingType())) {
      text.append(
          " It counted "
              + current.countingType()
              + " before, so the two settings are not comparable across the change.");
      text.append(countingAdvice(current.category(), spec.countingType()));
    }
    if (Boolean.FALSE.equals(spec.primaryForGoal())) {
      text.append(
          " As a secondary action it is still recorded and still reportable, but Smart Bidding no"
              + " longer bids towards it and it leaves the main conversions column.");
    }
    text.append(
        " The change applies to conversions recorded from now on — Google does not restate past"
            + " conversions, so the reported history keeps the old counting. Smart Bidding needs a"
            + " week or two to settle on the new numbers, so read performance over a window that"
            + " starts today rather than one that spans the change.");
    return text.toString();
  }

  // The two counting mistakes worth naming, and they point in opposite directions: a
  // purchase counted once per click hides repeat orders, a lead counted every time inflates
  // the number and the bidding built on it. Advice, never a refusal — an advertiser can have
  // a good reason for either.
  private String countingAdvice(String category, String countingType) {
    if ("PURCHASE".equals(category) && "ONE_PER_CLICK".equals(countingType)) {
      return " Note: on a purchase action this now counts only the first order after a click, so"
          + " a customer who buys twice registers as one conversion and revenue-based bidding sees"
          + " less than actually happened.";
    }
    if (!"PURCHASE".equals(category) && "MANY_PER_CLICK".equals(countingType)) {
      return " Note: on a lead-style action this now counts every submission after a click, so one"
          + " person filling the form three times registers as three conversions and Smart Bidding"
          + " chases the inflated number.";
    }
    return "";
  }
}
