package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDetailDto;
import com.loomascale.googleads.client.dto.GoogleAssetGroupQuery;
import com.loomascale.googleads.client.dto.GoogleAssetGroupSignalDto;
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
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Replaces the search themes of a Performance Max asset group — the phrases that tell Google
// what demand the asset group is for, and one of the few levers a PMax advertiser actually
// has over where the budget goes.
//
// asset_group_signal supports create and remove but never update, so a replace is a diff:
// the signals that dropped out are removed and the new themes created, both in one atomic
// mutate so a theme Google rejects on policy leaves the old set intact rather than half of
// it. Audience signals live on the same resource and are deliberately left alone — see the
// partition in execute().
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateSearchThemesTool implements AdsTool {

  private static final String SEARCH_THEME = "SEARCH_THEME";

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_search_themes";
  }

  @Override
  public String description() {
    return "Set the search themes of a Performance Max asset group — the phrases that tell Google"
        + " which demand this asset group is meant to capture, which is one of the few direct"
        + " controls a Performance Max campaign has. The list you pass REPLACES the whole set:"
        + " send every theme you want the asset group to have, including the ones already there."
        + " A theme that drops out of the list is deleted and this tool cannot bring it back."
        + " At most "
        + GoogleAdsEditSupport.MAX_SEARCH_THEMES
        + " themes of "
        + GoogleAdsEditSupport.MAX_SEARCH_THEME_LENGTH
        + " characters each. Audience signals on the same asset group are never touched. Read"
        + " the current themes first with google_list_asset_groups and include_search_themes.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "asset_group_id",
        "string",
        "Asset group whose search themes to set, as reported by google_list_asset_groups.");
    McpSchemas.stringArrayProp(
        schema,
        "search_themes",
        "The complete new set of search themes, at most "
            + GoogleAdsEditSupport.MAX_SEARCH_THEMES
            + " entries of "
            + GoogleAdsEditSupport.MAX_SEARCH_THEME_LENGTH
            + " characters or fewer. Write them the way someone would search, not as keywords"
            + " with match types. Pass an empty array to remove every search theme, which"
            + " leaves Google inferring intent entirely on its own.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    // search_themes is required rather than optional so the set cannot be wiped by
    // forgetting it: removing every theme has to be an explicit empty array.
    McpSchemas.required(schema, "asset_group_id", "search_themes");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "assetGroupId", "string", "Asset group that was updated.");
    McpSchemas.nullableProp(schema, "assetGroupName", "string", "Name of the asset group.");
    McpSchemas.nullableProp(schema, "campaignId", "string", "Campaign it belongs to.");
    McpSchemas.stringArrayProp(schema, "searchThemes", "The search themes now in effect.");
    McpSchemas.prop(schema, "added", "integer", "How many themes were created.");
    McpSchemas.prop(schema, "removed", "integer", "How many themes were deleted.");
    McpSchemas.stringArrayProp(
        schema,
        "skipped",
        "Themes that were already set under a different capitalisation, so nothing was written"
            + " for them. Google matches search themes case-insensitively.");
    McpSchemas.prop(
        schema,
        "audienceSignalCount",
        "integer",
        "Audience signals on this asset group, which this tool leaves untouched. Editing those"
            + " needs the Google Ads interface.");
    McpSchemas.required(
        schema,
        "assetGroupId",
        "searchThemes",
        "added",
        "removed",
        "skipped",
        "audienceSignalCount");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: search themes steer where a Performance Max budget goes across Search,
    // Shopping, YouTube, Display, Discover, Gmail and Maps, and a theme dropped from the
    // list is deleted with no way back. Idempotent: the list is an absolute set, so a
    // repeat call with the same array finds nothing to change and sends no mutate.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("asset_group_id")) {
      throw new McpToolException("asset_group_id is required.");
    }
    String assetGroupId = args.get("asset_group_id").asText();
    if (!args.has("search_themes")) {
      throw new McpToolException(
          "search_themes is required. Send the complete set you want the asset group to have,"
              + " or an empty array to remove them all.");
    }
    // Validated before the token work, so a bad theme costs no Google call.
    List<String> wanted = support.parseSearchThemes(args.get("search_themes"), "search_themes");

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleAssetGroupDetailDto group =
        support.requireAssetGroup(connection, token, customerId, loginCustomerId, assetGroupId);
    support.requireAssetGroupIsEditable(group, List.of());

    GoogleAssetGroupQuery query =
        new GoogleAssetGroupQuery(null, assetGroupId, null, null, null, 0, false, true);
    List<GoogleAssetGroupSignalDto> signals =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listAssetGroupSignals(token, customerId, loginCustomerId, query));

    // The partition is the safety property of this tool: an AssetGroupSignal is a oneof, and
    // a replace-set call carrying only search themes must not delete the audience signals
    // sharing the resource. Their count is reported so the model knows they exist.
    List<GoogleAssetGroupSignalDto> currentThemes =
        signals.stream().filter(signal -> SEARCH_THEME.equals(signal.kind())).toList();
    long audienceSignals = signals.size() - currentThemes.size();

    List<String> toCreate = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (String theme : wanted) {
      GoogleAssetGroupSignalDto existing = matching(currentThemes, theme);
      if (existing == null) {
        toCreate.add(theme);
      } else if (!existing.value().equals(theme)) {
        // A capitalisation-only difference. Rewriting it would mean removing and recreating
        // the same signal inside one atomic batch, which risks a duplicate-criterion
        // rejection, and case does not affect what Google matches.
        skipped.add("\"" + theme + "\" is already set as \"" + existing.value() + "\"");
      }
    }
    List<String> toRemove = new ArrayList<>();
    List<String> removedThemes = new ArrayList<>();
    for (GoogleAssetGroupSignalDto signal : currentThemes) {
      if (matchingText(wanted, signal.value()) == null) {
        toRemove.add(signal.resourceName());
        removedThemes.add(signal.value());
      }
    }

    String argsSummary =
        "assetGroup="
            + assetGroupId
            + ";themes="
            + wanted.size()
            + ";added="
            + toCreate.size()
            + ";removed="
            + toRemove.size();
    log.debug("google_update_search_themes user={} {}", userId, argsSummary);

    List<String> effective = effectiveThemes(currentThemes, wanted);
    if (toCreate.isEmpty() && toRemove.isEmpty()) {
      log.debug("google_update_search_themes user={} nothing to change", userId);
      return ToolResult.ok(
          "Asset group "
              + assetGroupId
              + " already has exactly those "
              + effective.size()
              + " search themes, so nothing was changed.",
          structured(group, assetGroupId, effective, 0, 0, skipped, audienceSignals));
    }

    try {
      adsService.call(
          connection,
          () ->
              adsService
                  .client()
                  .mutateAssetGroupSignals(
                      token, customerId, loginCustomerId, assetGroupId, toCreate, toRemove));
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_search_themes</b>\nUser: "
              + userId
              + "\nAsset group: "
              + assetGroupId
              + " ("
              + group.name()
              + ")\nCampaign: "
              + group.campaignName()
              + "\nThemes now: "
              + effective.size()
              + "\nAdded: "
              + String.join(", ", toCreate)
              + "\nRemoved: "
              + String.join(", ", removedThemes));
      return ToolResult.ok(
          text(group, effective, toCreate, removedThemes, skipped, audienceSignals),
          structured(
              group,
              assetGroupId,
              effective,
              toCreate.size(),
              toRemove.size(),
              skipped,
              audienceSignals));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, false, e.getMessage());
      throw e;
    }
  }

  private GoogleAssetGroupSignalDto matching(List<GoogleAssetGroupSignalDto> themes, String theme) {
    for (GoogleAssetGroupSignalDto signal : themes) {
      if (signal.value() != null && equalsIgnoringCase(signal.value(), theme)) {
        return signal;
      }
    }
    return null;
  }

  private String matchingText(List<String> themes, String theme) {
    for (String candidate : themes) {
      if (equalsIgnoringCase(candidate, theme)) {
        return candidate;
      }
    }
    return null;
  }

  private boolean equalsIgnoringCase(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }

  // What the asset group ends up with: the requested set, except that a theme already
  // present under a different capitalisation keeps the spelling Google is holding.
  private List<String> effectiveThemes(
      List<GoogleAssetGroupSignalDto> currentThemes, List<String> wanted) {
    List<String> effective = new ArrayList<>();
    for (String theme : wanted) {
      GoogleAssetGroupSignalDto existing = matching(currentThemes, theme);
      effective.add(existing == null ? theme : existing.value());
    }
    return effective;
  }

  private ObjectNode structured(
      GoogleAssetGroupDetailDto group,
      String assetGroupId,
      List<String> effective,
      int added,
      int removed,
      List<String> skipped,
      long audienceSignals) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("assetGroupId", assetGroupId);
    structured.put("assetGroupName", group.name());
    structured.put("campaignId", group.campaignId());
    effective.forEach(structured.putArray("searchThemes")::add);
    structured.put("added", added);
    structured.put("removed", removed);
    skipped.forEach(structured.putArray("skipped")::add);
    structured.put("audienceSignalCount", audienceSignals);
    return structured;
  }

  private String text(
      GoogleAssetGroupDetailDto group,
      List<String> effective,
      List<String> added,
      List<String> removed,
      List<String> skipped,
      long audienceSignals) {
    StringBuilder text = new StringBuilder();
    text.append("Asset group ")
        .append(group.name())
        .append(" in campaign ")
        .append(group.campaignName())
        .append(" now has ")
        .append(effective.size())
        .append(" search themes.\n");
    for (String theme : added) {
      text.append("  + ").append(theme).append("\n");
    }
    for (String theme : removed) {
      text.append("  - ").append(theme).append("\n");
    }
    for (String note : skipped) {
      text.append("  (kept) ").append(note).append("\n");
    }
    if (effective.isEmpty()) {
      text.append(
          "This asset group now has NO search themes, so Google will work out what to target"
              + " entirely on its own. That is rarely what an advertiser wants — add the themes"
              + " that describe the demand you are actually after.\n");
    }
    if (audienceSignals > 0) {
      text.append(audienceSignals)
          .append(
              audienceSignals == 1
                  ? " audience signal is also attached and was left alone.\n"
                  : " audience signals are also attached and were left alone.\n");
    }
    text.append(
        "Search themes are a signal, not a restriction: Google can still serve beyond"
            + " them, so check the search terms report afterwards.");
    return text.toString();
  }
}
