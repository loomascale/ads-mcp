package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleLanguageCriterionDto;
import com.loomascale.googleads.client.dto.GoogleLocationCriterionDto;
import com.loomascale.googleads.client.dto.GoogleResolvedLanguage;
import com.loomascale.googleads.client.dto.GoogleResolvedLocation;
import com.loomascale.googleads.client.dto.GoogleTargetingCriteriaDto;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Where and to whom a campaign may show: its location criteria (the Locations setting)
// and its language criteria (the Languages setting). Until this tool existed the account
// could be read by geography (google_get_geo_insights) but the settings that decide
// eligibility were invisible and unchangeable, so a campaign quietly targeting every
// country in every language looked identical to one scoped to a city and a language.
// Additive on purpose: no argument replaces the whole list, so a call cannot wipe
// targeting by omission — removals are named explicitly.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateCampaignTargetingTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_campaign_targeting";
  }

  @Override
  public String description() {
    return "Change where and in which languages a Google Ads campaign may show: add targeted"
        + " locations, add excluded locations, add languages, remove existing location or language"
        + " criteria, and set whether location targeting applies to people present in an area or"
        + " also to people interested in it. Location entries may be geo target constant ids or"
        + " place names — use google_find_locations when you only have a name; language entries may"
        + " be language constant ids, ISO codes (en, uk) or names (English). Read the current"
        + " targeting with google_list_campaigns and include_targeting. This tool only adds what you"
        + " name and removes what you name, with one exception: excluding a location the campaign"
        + " currently targets replaces that criterion, and targeting one it currently excludes does"
        + " the same, because Google cannot flip a criterion in place. It never replaces the whole"
        + " list. A campaign with no"
        + " targeted locations reaches every location on earth, and one with no languages shows to"
        + " every language.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "campaign_id", "string", "Campaign whose location targeting to change.");
    McpSchemas.stringArrayProp(
        schema,
        "target_locations",
        "Locations to start targeting — geo target constant ids or place names. At most "
            + GoogleAdsEditSupport.MAX_LOCATIONS
            + ". Locations already targeted are skipped.");
    McpSchemas.stringArrayProp(
        schema,
        "excluded_locations",
        "Locations to exclude — geo target constant ids or place names. At most "
            + GoogleAdsEditSupport.MAX_LOCATIONS
            + ". An exclusion beats a target, so use it to carve a region out of a wider one.");
    McpSchemas.stringArrayProp(
        schema,
        "languages",
        "Languages the campaign should show in — language constant ids, ISO codes (en, uk) or"
            + " names (English). At most "
            + GoogleAdsEditSupport.MAX_LOCATIONS
            + ". Languages already selected are skipped. Language targeting matches the language a"
            + " person browses Google in, not the language of the keyword.");
    McpSchemas.stringArrayProp(
        schema,
        "remove_criterion_ids",
        "Criterion ids of location or language criteria to delete, as reported by"
            + " google_list_campaigns with include_targeting. Ids that are already gone are"
            + " skipped.");
    ObjectNode geoTargetType =
        McpSchemas.prop(
            schema,
            "geo_target_type",
            "string",
            "PRESENCE reaches only people in the targeted locations. PRESENCE_OR_INTEREST, Google's"
                + " default, also reaches people elsewhere who show interest in them. Omit to leave"
                + " the setting alone.");
    McpSchemas.enumValues(geoTargetType, GoogleAdsEditSupport.GEO_TARGET_TYPES);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was updated.");
    McpSchemas.nullableProp(schema, "geoTargetType", "string", "Geo target type now in effect.");
    ObjectNode targeted =
        McpSchemas.objectArrayProp(schema, "targeted", "Locations now targeted by the campaign.");
    McpSchemas.nullableProp(
        targeted, "criterionId", "string", "Criterion id, needed to remove it.");
    McpSchemas.nullableProp(targeted, "id", "string", "Geo target constant id.");
    McpSchemas.nullableProp(targeted, "name", "string", "Canonical name of the location.");
    ObjectNode excluded =
        McpSchemas.objectArrayProp(schema, "excluded", "Locations the campaign excludes.");
    McpSchemas.nullableProp(
        excluded, "criterionId", "string", "Criterion id, needed to remove it.");
    McpSchemas.nullableProp(excluded, "id", "string", "Geo target constant id.");
    McpSchemas.nullableProp(excluded, "name", "string", "Canonical name of the location.");
    ObjectNode language =
        McpSchemas.objectArrayProp(
            schema,
            "languages",
            "Languages the campaign now shows in. Empty means every language.");
    McpSchemas.nullableProp(
        language, "criterionId", "string", "Criterion id, needed to remove it.");
    McpSchemas.nullableProp(language, "id", "string", "Language constant id.");
    McpSchemas.nullableProp(language, "code", "string", "ISO code, e.g. en.");
    McpSchemas.nullableProp(language, "name", "string", "Language name, e.g. English.");
    McpSchemas.prop(schema, "added", "integer", "How many criteria were created.");
    McpSchemas.prop(schema, "removed", "integer", "How many criteria were deleted.");
    McpSchemas.stringArrayProp(
        schema,
        "skipped",
        "What the call did not have to do: locations already set, criterion ids already gone.");
    McpSchemas.required(
        schema, "campaignId", "targeted", "excluded", "languages", "added", "removed");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: location targeting decides where the budget is spent, and removing a
    // criterion stops delivery in that area — or widens the campaign to everywhere when
    // it was the last one. Idempotent: adds skip what is already set and removes skip
    // what is already gone, so a repeat call lands in the same state.
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
    List<String> targetEntries = entries(args, "target_locations");
    List<String> excludeEntries = entries(args, "excluded_locations");
    List<String> languageEntries = entries(args, "languages");
    List<String> removeCriterionIds = entries(args, "remove_criterion_ids");
    String geoTargetType = geoTargetType(args);
    if (targetEntries.isEmpty()
        && excludeEntries.isEmpty()
        && languageEntries.isEmpty()
        && removeCriterionIds.isEmpty()
        && geoTargetType == null) {
      throw new McpToolException(
          "Provide target_locations, excluded_locations, languages, remove_criterion_ids,"
              + " geo_target_type, or any combination — there is nothing to change.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleCampaignDto campaign =
        support.requireCampaign(connection, token, customerId, loginCustomerId, campaignId);

    List<GoogleResolvedLocation> toTarget =
        support.resolveLocations(
            connection, token, customerId, loginCustomerId, targetEntries, "target_locations");
    List<GoogleResolvedLocation> toExclude =
        support.resolveLocations(
            connection, token, customerId, loginCustomerId, excludeEntries, "excluded_locations");
    requireNoOverlap(toTarget, toExclude);
    List<GoogleResolvedLanguage> toSpeak =
        support.resolveLanguages(
            connection, token, customerId, loginCustomerId, languageEntries, "languages");

    GoogleTargetingCriteriaDto before =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listTargetingCriteria(token, customerId, loginCustomerId, campaignId));
    List<GoogleLocationCriterionDto> current = before.locations();
    List<GoogleLanguageCriterionDto> currentLanguages = before.languages();

    List<String> skipped = new ArrayList<>();
    // A criterion cannot be flipped from targeted to excluded in place —
    // CampaignCriterion.negative is immutable, and a create against an existing
    // opposite-polarity criterion fails with IMMUTABLE_FIELD. The conflicting criterion
    // is collected here and removed before the new one is created.
    List<String> flipped = new ArrayList<>();
    List<String> flipRemovals = new ArrayList<>();
    List<String> targetIds = newCriteria(toTarget, current, false, skipped, flipped, flipRemovals);
    List<String> excludeIds = newCriteria(toExclude, current, true, skipped, flipped, flipRemovals);
    List<String> languageIds = newLanguageCriteria(toSpeak, currentLanguages, skipped);
    List<String> removeIds =
        existingCriterionIds(removeCriterionIds, current, currentLanguages, skipped);
    for (String criterionId : flipRemovals) {
      if (!removeIds.contains(criterionId)) {
        removeIds.add(criterionId);
      }
    }

    String argsSummary =
        "campaign="
            + campaignId
            + ";target="
            + targetIds
            + ";exclude="
            + excludeIds
            + ";languages="
            + languageIds
            + ";remove="
            + removeIds
            + ";geoType="
            + geoTargetType;
    log.debug("google_update_campaign_targeting user={} {}", userId, argsSummary);
    if (targetIds.isEmpty()
        && excludeIds.isEmpty()
        && languageIds.isEmpty()
        && removeIds.isEmpty()
        && geoTargetType == null) {
      // Everything asked for is already the case; say so instead of reporting a change.
      return ToolResult.ok(
          "Campaign \""
              + campaign.name()
              + "\" already has this targeting, so nothing changed. Skipped: "
              + String.join("; ", skipped)
              + ".",
          result(
              campaignId,
              campaign.positiveGeoTargetType(),
              current,
              currentLanguages,
              0,
              0,
              skipped));
    }

    String campaignResourceName = "customers/" + customerId + "/campaigns/" + campaignId;
    try {
      // Removals first: a create for a location whose opposite-polarity criterion still
      // exists is rejected outright.
      if (!removeIds.isEmpty()) {
        adsService.call(
            connection,
            () -> {
              adsService
                  .client()
                  .removeCampaignCriteria(
                      token, customerId, loginCustomerId, campaignId, removeIds);
              return null;
            });
      }
      if (!targetIds.isEmpty()) {
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
                        targetIds,
                        false));
      }
      if (!excludeIds.isEmpty()) {
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
                        excludeIds,
                        true));
      }
      if (!languageIds.isEmpty()) {
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .addLanguageCriteria(
                        token, customerId, loginCustomerId, campaignResourceName, languageIds));
      }
      if (geoTargetType != null) {
        adsService.call(
            connection,
            () -> {
              adsService
                  .client()
                  .updatePositiveGeoTargetType(
                      token, customerId, loginCustomerId, campaignId, geoTargetType);
              return null;
            });
      }

      GoogleTargetingCriteriaDto reread =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .listTargetingCriteria(token, customerId, loginCustomerId, campaignId));
      List<GoogleLocationCriterionDto> after = reread.locations();
      List<GoogleLanguageCriterionDto> afterLanguages = reread.languages();

      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_campaign_targeting</b>\nUser: "
              + userId
              + "\nCampaign: "
              + campaign.name()
              + " ("
              + campaignId
              + ")\nTargeted added: "
              + names(toTarget, targetIds)
              + "\nExcluded added: "
              + names(toExclude, excludeIds)
              + "\nLanguages added: "
              + languageNames(toSpeak, languageIds)
              + "\nRemoved criteria: "
              + (removeIds.isEmpty() ? "none" : String.join(", ", removeIds))
              + "\nGeo target type: "
              + (geoTargetType == null ? "unchanged" : geoTargetType));

      ObjectNode structured =
          result(
              campaignId,
              geoTargetType == null ? campaign.positiveGeoTargetType() : geoTargetType,
              after,
              afterLanguages,
              targetIds.size() + excludeIds.size() + languageIds.size(),
              removeIds.size(),
              skipped);

      StringBuilder text = new StringBuilder("Campaign \"" + campaign.name() + "\":");
      if (!targetIds.isEmpty()) {
        text.append(" now targets ").append(names(toTarget, targetIds)).append(".");
      }
      if (!excludeIds.isEmpty()) {
        text.append(" now excludes ").append(names(toExclude, excludeIds)).append(".");
      }
      if (!languageIds.isEmpty()) {
        text.append(" now shows in ").append(languageNames(toSpeak, languageIds)).append(".");
      }
      if (!removeIds.isEmpty()) {
        text.append(" removed ").append(removeIds.size()).append(" targeting criteria.");
      }
      if (!flipped.isEmpty()) {
        text.append(" ").append(String.join("; ", flipped)).append(".");
      }
      if (geoTargetType != null) {
        text.append(" Geo target type set to ").append(geoTargetType).append(".");
      }
      if (!skipped.isEmpty()) {
        text.append(" Skipped: ").append(String.join("; ", skipped)).append(".");
      }
      if (after.stream().allMatch(GoogleLocationCriterionDto::negative)) {
        text.append(
            " Warning: the campaign now has no targeted location, which Google reads as ALL"
                + " locations — it can serve anywhere in the world. Add a location to scope it"
                + " again.");
      }
      if (afterLanguages.isEmpty()) {
        text.append(
            " Warning: the campaign now has no language criterion, which Google reads as ALL"
                + " languages — its ads can show to people browsing Google in any language.");
      }
      return ToolResult.ok(text.toString(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, false, e.getMessage());
      throw e;
    }
  }

  private List<String> entries(JsonNode args, String field) {
    if (!args.has(field) || args.get(field).isNull()) {
      return List.of();
    }
    if (!args.get(field).isArray()) {
      throw new McpToolException(field + " must be an array.");
    }
    List<String> entries = new ArrayList<>();
    for (JsonNode entry : args.get(field)) {
      String value = entry.asText("").trim();
      if (!value.isBlank()) {
        entries.add(value);
      }
    }
    return entries;
  }

  private String geoTargetType(JsonNode args) {
    if (!args.hasNonNull("geo_target_type")) {
      return null;
    }
    String type = args.get("geo_target_type").asText().trim().toUpperCase();
    if (!GoogleAdsEditSupport.GEO_TARGET_TYPES.contains(type)) {
      throw new McpToolException(
          "geo_target_type must be one of: "
              + String.join(", ", GoogleAdsEditSupport.GEO_TARGET_TYPES));
    }
    return type;
  }

  private void requireNoOverlap(
      List<GoogleResolvedLocation> toTarget, List<GoogleResolvedLocation> toExclude) {
    for (GoogleResolvedLocation target : toTarget) {
      for (GoogleResolvedLocation exclude : toExclude) {
        if (target.geoTargetId().equals(exclude.geoTargetId())) {
          throw new McpToolException(
              "\""
                  + target.name()
                  + "\" is in both target_locations and excluded_locations. Pick one — an exclusion"
                  + " would cancel the targeting.");
        }
      }
    }
  }

  // Geo target ids to create: the ones not already attached with the same polarity. A
  // location attached with the OTHER polarity is a conflict, not a skip — Google cannot
  // turn a targeted criterion into an excluded one, so the old criterion is queued for
  // removal and the new one is still created.
  private List<String> newCriteria(
      List<GoogleResolvedLocation> wanted,
      List<GoogleLocationCriterionDto> current,
      boolean negative,
      List<String> skipped,
      List<String> flipped,
      List<String> flipRemovals) {
    Set<String> alreadySet = new LinkedHashSet<>();
    current.stream()
        .filter(criterion -> criterion.negative() == negative)
        .forEach(criterion -> alreadySet.add(criterion.geoTargetId()));
    List<String> ids = new ArrayList<>();
    for (GoogleResolvedLocation location : wanted) {
      if (alreadySet.contains(location.geoTargetId())) {
        skipped.add(location.name() + " is already " + (negative ? "excluded" : "targeted"));
        continue;
      }
      current.stream()
          .filter(criterion -> criterion.negative() != negative)
          .filter(criterion -> location.geoTargetId().equals(criterion.geoTargetId()))
          .findFirst()
          .ifPresent(
              conflict -> {
                flipRemovals.add(conflict.criterionId());
                flipped.add(
                    location.name()
                        + " was "
                        + (negative ? "targeted" : "excluded")
                        + ", so criterion "
                        + conflict.criterionId()
                        + " was removed and replaced with "
                        + (negative ? "an exclusion" : "targeting"));
              });
      ids.add(location.geoTargetId());
    }
    return ids;
  }

  // Language constant ids the campaign does not already carry.
  private List<String> newLanguageCriteria(
      List<GoogleResolvedLanguage> wanted,
      List<GoogleLanguageCriterionDto> current,
      List<String> skipped) {
    Set<String> alreadySet = new LinkedHashSet<>();
    current.forEach(criterion -> alreadySet.add(criterion.languageId()));
    List<String> ids = new ArrayList<>();
    for (GoogleResolvedLanguage language : wanted) {
      if (alreadySet.contains(language.id())) {
        skipped.add(language.name() + " is already selected");
      } else {
        ids.add(language.id());
      }
    }
    return ids;
  }

  // Location and language criteria share one id space per campaign, so one removal
  // argument covers both — and an id belonging to neither is skipped, not fatal.
  private List<String> existingCriterionIds(
      List<String> requested,
      List<GoogleLocationCriterionDto> current,
      List<GoogleLanguageCriterionDto> currentLanguages,
      List<String> skipped) {
    Set<String> present = new LinkedHashSet<>();
    current.forEach(criterion -> present.add(criterion.criterionId()));
    currentLanguages.forEach(criterion -> present.add(criterion.criterionId()));
    List<String> ids = new ArrayList<>();
    for (String criterionId : requested) {
      if (present.contains(criterionId)) {
        ids.add(criterionId);
      } else {
        skipped.add("criterion " + criterionId + " is not a targeting criterion of this campaign");
      }
    }
    return ids;
  }

  private String names(List<GoogleResolvedLocation> resolved, List<String> ids) {
    List<String> names =
        resolved.stream()
            .filter(location -> ids.contains(location.geoTargetId()))
            .map(GoogleResolvedLocation::name)
            .toList();
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  private String languageNames(List<GoogleResolvedLanguage> resolved, List<String> ids) {
    List<String> names =
        resolved.stream()
            .filter(language -> ids.contains(language.id()))
            .map(GoogleResolvedLanguage::name)
            .toList();
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  private ObjectNode result(
      String campaignId,
      String geoTargetType,
      List<GoogleLocationCriterionDto> criteria,
      List<GoogleLanguageCriterionDto> languages,
      int added,
      int removed,
      List<String> skipped) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("campaignId", campaignId);
    structured.put("geoTargetType", geoTargetType);
    writeCriteria(structured.putArray("targeted"), criteria, false);
    writeCriteria(structured.putArray("excluded"), criteria, true);
    ArrayNode languageArr = structured.putArray("languages");
    languages.forEach(
        language -> {
          ObjectNode node = languageArr.addObject();
          node.put("criterionId", language.criterionId());
          node.put("id", language.languageId());
          node.put("code", language.code());
          node.put("name", language.name());
        });
    structured.put("added", added);
    structured.put("removed", removed);
    ArrayNode skippedArr = structured.putArray("skipped");
    skipped.forEach(skippedArr::add);
    return structured;
  }

  private void writeCriteria(
      ArrayNode arr, List<GoogleLocationCriterionDto> criteria, boolean negative) {
    criteria.stream()
        .filter(criterion -> criterion.negative() == negative)
        .forEach(
            criterion -> {
              ObjectNode node = arr.addObject();
              node.put("criterionId", criterion.criterionId());
              node.put("id", criterion.geoTargetId());
              node.put("name", criterion.name());
            });
  }
}
