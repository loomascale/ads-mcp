package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDetailDto;
import com.loomascale.googleads.client.dto.GoogleAssetLinkDto;
import com.loomascale.googleads.client.dto.GoogleAssetLinkSpec;
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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Rewrites the copy of a Performance Max asset group. Until this tool existed the surface
// could read a PMax asset group, count its assets and say which ones Google had stopped
// serving, and then do nothing about any of it — the assistant's own summary of the gap was
// "I can audit these PMax settings, but the available controls do not let me directly edit
// PMax asset-group headlines/descriptions".
//
// The edit is a diff, not a replacement. Text assets in Google Ads are immutable and shared
// by content, so a text that is already linked keeps its existing asset and with it the
// per-asset primaryStatus history that a PMax audit reads; only genuinely new text is
// created and linked, and only dropped text is unlinked. The shared Asset itself is never
// removed — another asset group may well be using it.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateAssetGroupAssetsTool implements AdsTool {

  private static final String HEADLINE = "HEADLINE";
  private static final String LONG_HEADLINE = "LONG_HEADLINE";
  private static final String DESCRIPTION = "DESCRIPTION";

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_asset_group_assets";
  }

  @Override
  public String description() {
    return "Rewrite the text of a Performance Max asset group: its headlines, long headlines and"
        + " descriptions. Each list you pass REPLACES the whole list for that slot — send every"
        + " headline you want the asset group to have, not just the new ones — and a slot you"
        + " omit is left untouched. Text that is already there keeps its existing asset and its"
        + " performance history, so repeating a headline you want to keep costs nothing. Read the"
        + " current copy first with google_list_asset_groups and include_assets. Google's minimums"
        + " apply and cannot be crossed: 3 headlines, 1 long headline, 2 descriptions, with at"
        + " least one description of 60 characters or fewer. For images, logos and video use"
        + " google_update_asset_group_media; for search themes google_update_search_themes; for a"
        + " responsive search ad google_update_ad.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "asset_group_id",
        "string",
        "Asset group to rewrite, as reported by google_list_asset_groups.");
    ObjectNode headlines =
        McpSchemas.objectArrayProp(
            schema,
            "headlines",
            "Complete new set of headlines, "
                + GoogleAdsEditSupport.PMAX_MIN_HEADLINES
                + "-"
                + GoogleAdsEditSupport.PMAX_MAX_HEADLINES
                + " entries of at most "
                + GoogleAdsEditSupport.PMAX_MAX_HEADLINE_LENGTH
                + " characters. An entry may be a plain string or an object with a text."
                + " Performance Max assets are never pinned. Omit to leave the headlines alone.");
    McpSchemas.prop(headlines, "text", "string", "Headline text.");
    ObjectNode longHeadlines =
        McpSchemas.objectArrayProp(
            schema,
            "long_headlines",
            "Complete new set of long headlines, "
                + GoogleAdsEditSupport.PMAX_MIN_LONG_HEADLINES
                + "-"
                + GoogleAdsEditSupport.PMAX_MAX_LONG_HEADLINES
                + " entries of at most "
                + GoogleAdsEditSupport.PMAX_MAX_LONG_HEADLINE_LENGTH
                + " characters. These are the headlines Google uses where there is room for a"
                + " full sentence. Omit to leave them alone.");
    McpSchemas.prop(longHeadlines, "text", "string", "Long headline text.");
    ObjectNode descriptions =
        McpSchemas.objectArrayProp(
            schema,
            "descriptions",
            "Complete new set of descriptions, "
                + GoogleAdsEditSupport.PMAX_MIN_DESCRIPTIONS
                + "-"
                + GoogleAdsEditSupport.PMAX_MAX_DESCRIPTIONS
                + " entries of at most "
                + GoogleAdsEditSupport.PMAX_MAX_DESCRIPTION_LENGTH
                + " characters, of which at least one must be "
                + GoogleAdsEditSupport.PMAX_SHORT_DESCRIPTION_LENGTH
                + " characters or fewer — Google refuses an asset group whose descriptions are"
                + " all long. Omit to leave them alone.");
    McpSchemas.prop(
        descriptions,
        "text",
        "string",
        "Description text. Write one of them at "
            + GoogleAdsEditSupport.PMAX_SHORT_DESCRIPTION_LENGTH
            + " characters or fewer before writing the long ones — the call is refused when"
            + " every description is longer than that.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "asset_group_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "assetGroupId", "string", "Asset group that was rewritten.");
    McpSchemas.prop(
        schema,
        "changed",
        "boolean",
        "False when every text you passed was already in place, in which case nothing was sent"
            + " to Google at all.");
    ObjectNode field =
        McpSchemas.objectArrayProp(schema, "fields", "One entry per slot this call touched.");
    McpSchemas.prop(field, "fieldType", "string", "HEADLINE, LONG_HEADLINE or DESCRIPTION.");
    McpSchemas.stringArrayProp(field, "added", "Text that was created and linked.");
    McpSchemas.stringArrayProp(field, "removed", "Text that was unlinked from the asset group.");
    McpSchemas.stringArrayProp(
        field, "kept", "Text that was already linked and kept its existing asset.");
    McpSchemas.prop(field, "countAfter", "integer", "How many assets fill the slot now.");
    McpSchemas.nullableProp(schema, "assetGroupName", "string", "Name of the asset group.");
    McpSchemas.nullableProp(schema, "campaignId", "string", "Campaign it belongs to.");
    McpSchemas.nullableProp(schema, "campaignName", "string", "Name of that campaign.");
    McpSchemas.nullableProp(
        schema,
        "adStrengthBefore",
        "string",
        "Ad strength the asset group carried before the edit. Google recomputes it after a"
            + " change, so read it again with google_list_asset_groups to see the effect.");
    McpSchemas.required(schema, "assetGroupId", "changed", "fields");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: an asset link this call does not repeat is removed, and Google cannot
    // restore it. The shared Asset survives, but the link, its per-asset primaryStatus
    // history and its contribution to ad strength are gone, and there is no per-link pause
    // to soften it. Idempotent: every list is an absolute value, so a second identical call
    // diffs to nothing — and this tool detects that and sends no mutate at all.
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

    // Parsed before any token work, so a bad list costs no Google call — and, more to the
    // point, so a count Google would reject can never leave orphaned assets behind: the
    // asset create and the link mutate are separate requests and an Asset cannot be
    // deleted through the API.
    Map<String, List<String>> requested = new LinkedHashMap<>();
    if (args.has("headlines")) {
      requested.put(
          HEADLINE,
          support.parseAssetTexts(
              args.get("headlines"),
              "headlines",
              GoogleAdsEditSupport.PMAX_MIN_HEADLINES,
              GoogleAdsEditSupport.PMAX_MAX_HEADLINES,
              GoogleAdsEditSupport.PMAX_MAX_HEADLINE_LENGTH));
    }
    if (args.has("long_headlines")) {
      requested.put(
          LONG_HEADLINE,
          support.parseAssetTexts(
              args.get("long_headlines"),
              "long_headlines",
              GoogleAdsEditSupport.PMAX_MIN_LONG_HEADLINES,
              GoogleAdsEditSupport.PMAX_MAX_LONG_HEADLINES,
              GoogleAdsEditSupport.PMAX_MAX_LONG_HEADLINE_LENGTH));
    }
    if (args.has("descriptions")) {
      List<String> descriptions =
          support.parseAssetTexts(
              args.get("descriptions"),
              "descriptions",
              GoogleAdsEditSupport.PMAX_MIN_DESCRIPTIONS,
              GoogleAdsEditSupport.PMAX_MAX_DESCRIPTIONS,
              GoogleAdsEditSupport.PMAX_MAX_DESCRIPTION_LENGTH);
      support.requireShortDescription(descriptions);
      requested.put(DESCRIPTION, descriptions);
    }
    if (requested.isEmpty()) {
      throw new McpToolException(
          "Provide headlines, long_headlines, descriptions, or any combination — there is"
              + " nothing to change.");
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleAssetGroupDetailDto group =
        support.requireAssetGroup(connection, token, customerId, loginCustomerId, assetGroupId);
    support.requireAssetGroupIsEditable(group, requested.keySet());

    List<GoogleAssetLinkDto> links =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listAssetGroupAssetLinks(token, customerId, loginCustomerId, assetGroupId));
    if (links.size() >= GoogleAdsApiClient.MAX_ASSET_LINK_ROWS) {
      throw new McpToolException(
          "Asset group "
              + assetGroupId
              + " reports at least "
              + links.size()
              + " linked assets, which is more than this tool reads in one go. Diffing against a"
              + " partial view could unlink the wrong assets, so the edit was not attempted.");
    }

    List<String> addedTexts = new ArrayList<>();
    List<GoogleAssetLinkSpec> toUnlink = new ArrayList<>();
    Map<String, List<String>> kept = new LinkedHashMap<>();
    Map<String, List<String>> added = new LinkedHashMap<>();
    Map<String, List<String>> removed = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : requested.entrySet()) {
      String fieldType = entry.getKey();
      List<GoogleAssetLinkDto> current = textLinksOf(links, fieldType);
      List<String> currentTexts = current.stream().map(GoogleAssetLinkDto::text).toList();

      List<String> keptTexts = new ArrayList<>();
      List<String> addedForField = new ArrayList<>();
      for (String text : entry.getValue()) {
        if (currentTexts.contains(text)) {
          keptTexts.add(text);
        } else {
          addedForField.add(text);
          addedTexts.add(text);
        }
      }
      List<String> removedForField = new ArrayList<>();
      for (GoogleAssetLinkDto link : current) {
        if (!entry.getValue().contains(link.text())) {
          removedForField.add(link.text());
          toUnlink.add(
              new GoogleAssetLinkSpec(link.assetResourceName(), link.assetId(), fieldType));
        }
      }
      kept.put(fieldType, keptTexts);
      added.put(fieldType, addedForField);
      removed.put(fieldType, removedForField);
    }

    String argsSummary =
        "assetGroup="
            + assetGroupId
            + ";added="
            + addedTexts.size()
            + ";removed="
            + toUnlink.size()
            + ";fields="
            + String.join("/", requested.keySet());
    log.debug("google_update_asset_group_assets user={} {}", userId, argsSummary);

    if (addedTexts.isEmpty() && toUnlink.isEmpty()) {
      // Saying "done" after sending nothing would be a lie of the kind that started this
      // whole tool, so the no-op is reported as one.
      log.debug("google_update_asset_group_assets user={} nothing to change", userId);
      return ToolResult.ok(
          "Asset group "
              + assetGroupId
              + " already has exactly the copy you asked for, so nothing was changed.",
          structured(group, assetGroupId, false, requested, added, removed, kept));
    }

    try {
      adsService.call(
          connection,
          () -> {
            List<String> assetResourceNames =
                addedTexts.isEmpty()
                    ? List.of()
                    : adsService
                        .client()
                        .createTextAssets(token, customerId, loginCustomerId, addedTexts);
            List<GoogleAssetLinkSpec> toLink = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : added.entrySet()) {
              for (String text : entry.getValue()) {
                // createTextAssets returns resource names in the order the texts went in,
                // which is how each one finds the slot it was meant for.
                toLink.add(
                    new GoogleAssetLinkSpec(
                        assetResourceNames.get(addedTexts.indexOf(text)), null, entry.getKey()));
              }
            }
            adsService
                .client()
                .mutateAssetGroupAssetLinks(
                    token, customerId, loginCustomerId, assetGroupId, toLink, toUnlink);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_asset_group_assets</b>\nUser: "
              + userId
              + "\nAsset group: "
              + assetGroupId
              + " ("
              + group.name()
              + ")\nCampaign: "
              + group.campaignName()
              + "\nAdded: "
              + addedTexts.size()
              + "\nRemoved: "
              + toUnlink.size());
      return ToolResult.ok(
          text(group, assetGroupId, requested, added, removed, kept),
          structured(group, assetGroupId, true, requested, added, removed, kept));
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, false, e.getMessage());
      String hint = support.assetErrorHint(e.getMessage());
      if (!hint.isEmpty() && e instanceof McpToolException) {
        throw new McpToolException(e.getMessage() + " " + hint);
      }
      throw e;
    }
  }

  // Only text assets can be diffed on their text; an image linked to the same slot has none.
  private List<GoogleAssetLinkDto> textLinksOf(List<GoogleAssetLinkDto> links, String fieldType) {
    return links.stream()
        .filter(link -> fieldType.equals(link.fieldType()))
        .filter(link -> link.text() != null && !link.text().isBlank())
        .toList();
  }

  private ObjectNode structured(
      GoogleAssetGroupDetailDto group,
      String assetGroupId,
      boolean changed,
      Map<String, List<String>> requested,
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("assetGroupId", assetGroupId);
    structured.put("changed", changed);
    ArrayNode fields = structured.putArray("fields");
    for (String fieldType : requested.keySet()) {
      ObjectNode node = fields.addObject();
      node.put("fieldType", fieldType);
      added.get(fieldType).forEach(node.putArray("added")::add);
      removed.get(fieldType).forEach(node.putArray("removed")::add);
      kept.get(fieldType).forEach(node.putArray("kept")::add);
      node.put("countAfter", requested.get(fieldType).size());
    }
    structured.put("assetGroupName", group.name());
    structured.put("campaignId", group.campaignId());
    structured.put("campaignName", group.campaignName());
    structured.put("adStrengthBefore", group.adStrength());
    return structured;
  }

  private String text(
      GoogleAssetGroupDetailDto group,
      String assetGroupId,
      Map<String, List<String>> requested,
      Map<String, List<String>> added,
      Map<String, List<String>> removed,
      Map<String, List<String>> kept) {
    StringBuilder text = new StringBuilder();
    text.append("Rewrote asset group ")
        .append(group.name() == null ? assetGroupId : group.name())
        .append(" in campaign ")
        .append(group.campaignName())
        .append("\n");
    for (String fieldType : requested.keySet()) {
      text.append(fieldType)
          .append(": ")
          .append(requested.get(fieldType).size())
          .append(" now (")
          .append(added.get(fieldType).size())
          .append(" added, ")
          .append(removed.get(fieldType).size())
          .append(" removed, ")
          .append(kept.get(fieldType).size())
          .append(" kept)\n");
      for (String value : added.get(fieldType)) {
        text.append("  + ").append(value).append("\n");
      }
      for (String value : removed.get(fieldType)) {
        text.append("  - ").append(value).append("\n");
      }
    }
    text.append(
        "Google reviews changed assets again and recomputes ad strength, so read the group"
            + " back with google_list_asset_groups in a while to see the effect.");
    return text.toString();
  }
}
