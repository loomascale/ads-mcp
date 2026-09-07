package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAssetGroupDetailDto;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// The asset group's own settings, as opposed to the assets inside it: its name, whether it
// serves, where its clicks land and the display path shown after the domain.
//
// This is also the only way to switch an asset group off and back on. An asset group built
// by google_create_pmax_campaign starts ENABLED — its PAUSED campaign is what holds the
// spend — so pausing one here stops it while the rest of the campaign carries on.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateAssetGroupTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_asset_group";
  }

  @Override
  public String description() {
    return "Change the settings of a Performance Max asset group: its name, whether it is enabled"
        + " or paused, the landing pages it sends clicks to and the display paths shown after the"
        + " domain. This is how one asset group of a campaign is stopped or restarted without"
        + " touching the others. final_urls REPLACES the whole list, and a field you omit is left"
        + " untouched. An"
        + " asset group cannot be moved to a different campaign. For the headlines and"
        + " descriptions inside the group use google_update_asset_group_assets, and for its"
        + " search themes google_update_search_themes.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema,
        "asset_group_id",
        "string",
        "Asset group to change, as reported by google_list_asset_groups.");
    McpSchemas.prop(schema, "name", "string", "New name for the asset group.");
    ObjectNode status =
        McpSchemas.prop(
            schema,
            "status",
            "string",
            "PAUSED stops this asset group serving while the rest of the campaign carries on;"
                + " ENABLED makes it eligible to spend the campaign budget again.");
    McpSchemas.enumValues(status, GoogleAdsEditSupport.STATUSES);
    McpSchemas.stringArrayProp(
        schema,
        "final_urls",
        "Complete new list of landing pages for this asset group, as http(s) URLs. Replaces the"
            + " whole list; an asset group must keep at least one.");
    McpSchemas.prop(
        schema,
        "path1",
        "string",
        "First display path segment shown after the domain, at most "
            + GoogleAdsEditSupport.MAX_PATH_LENGTH
            + " characters. Pass an empty string to remove it.");
    McpSchemas.prop(schema, "path2", "string", "Second display path segment. Requires path1.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "asset_group_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "assetGroupId", "string", "Asset group that was changed.");
    McpSchemas.prop(schema, "status", "string", "Status now in effect.");
    McpSchemas.stringArrayProp(schema, "finalUrls", "Landing pages now in effect.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign it belongs to.");
    McpSchemas.nullableProp(schema, "name", "string", "Name now in effect.");
    McpSchemas.nullableProp(schema, "campaignName", "string", "Name of that campaign.");
    McpSchemas.nullableProp(schema, "path1", "string", "First display path now in effect.");
    McpSchemas.nullableProp(schema, "path2", "string", "Second display path now in effect.");
    McpSchemas.nullableProp(
        schema, "primaryStatus", "string", "Whether the asset group can serve.");
    McpSchemas.nullableProp(schema, "adStrength", "string", "Ad strength before the change.");
    McpSchemas.required(schema, "assetGroupId", "status", "finalUrls", "campaignId");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: the final URL list is replaced wholesale, PAUSED stops the asset group
    // serving, and ENABLED can start real spend within the campaign's budget. Idempotent:
    // every field is an absolute value, so a repeat call leaves the same end state.
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
    // Presence rather than non-nullness, because "" is how a display path is removed.
    boolean changesSomething =
        args.hasNonNull("name")
            || args.hasNonNull("status")
            || args.has("final_urls")
            || args.has("path1")
            || args.has("path2");
    if (!changesSomething) {
      throw new McpToolException(
          "Provide name, status, final_urls, path1, path2, or any combination — there is nothing"
              + " to change.");
    }

    String newName = args.hasNonNull("name") ? args.get("name").asText().trim() : null;
    if (newName != null && newName.isBlank()) {
      throw new McpToolException("name cannot be blank.");
    }
    String status = null;
    if (args.hasNonNull("status")) {
      status = args.get("status").asText().trim().toUpperCase();
      if (!GoogleAdsEditSupport.STATUSES.contains(status)) {
        throw new McpToolException(
            "status must be one of: " + String.join(", ", GoogleAdsEditSupport.STATUSES) + ".");
      }
    }
    List<String> finalUrls = null;
    if (args.has("final_urls")) {
      JsonNode urls = args.get("final_urls");
      if (!urls.isArray()) {
        throw new McpToolException("final_urls must be an array of http(s) URLs.");
      }
      finalUrls = new ArrayList<>();
      for (JsonNode url : urls) {
        finalUrls.add(support.requireHttpUrl(url.asText("").trim(), "final_urls"));
      }
      if (finalUrls.isEmpty()) {
        // Google requires at least one, and refusing locally beats a FieldError.REQUIRED.
        throw new McpToolException(
            "An asset group needs at least one final URL, so final_urls cannot be empty. To stop"
                + " it serving, pass status PAUSED instead.");
      }
    }
    String path1 = path(args, "path1");
    String path2 = path(args, "path2");

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleAssetGroupDetailDto group =
        support.requireAssetGroup(connection, token, customerId, loginCustomerId, assetGroupId);
    support.requireAssetGroupIsEditable(group, List.of());

    // Google rejects a second display path with no first one, whether the first is being set
    // now or was already there.
    String effectivePath1 = path1 != null ? path1 : group.path1();
    if (path2 != null && !path2.isBlank() && (effectivePath1 == null || effectivePath1.isBlank())) {
      throw new McpToolException("path2 needs path1 — set path1 as well, or drop path2.");
    }

    String argsSummary =
        "assetGroup="
            + assetGroupId
            + ";name="
            + newName
            + ";status="
            + status
            + ";finalUrls="
            + (finalUrls == null ? "unchanged" : finalUrls.size())
            + ";path1="
            + path1
            + ";path2="
            + path2;
    log.debug("google_update_asset_group user={} {}", userId, argsSummary);

    List<String> effectiveUrls = finalUrls != null ? finalUrls : group.finalUrls();
    String effectiveStatus = status != null ? status : group.status();
    List<String> writtenFinalUrls = finalUrls;
    String writtenStatus = status;
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateAssetGroup(
                    token,
                    customerId,
                    loginCustomerId,
                    assetGroupId,
                    newName,
                    writtenStatus,
                    writtenFinalUrls,
                    path1,
                    path2);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_asset_group</b>\nUser: "
              + userId
              + "\nAsset group: "
              + assetGroupId
              + " ("
              + group.name()
              + ")\nCampaign: "
              + group.campaignName()
              + "\nStatus: "
              + effectiveStatus
              + "\nFinal URLs: "
              + String.join(", ", effectiveUrls));

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("assetGroupId", assetGroupId);
      structured.put("status", effectiveStatus);
      effectiveUrls.forEach(structured.putArray("finalUrls")::add);
      structured.put("campaignId", group.campaignId());
      structured.put("name", newName != null ? newName : group.name());
      structured.put("campaignName", group.campaignName());
      structured.put("path1", path1 != null ? path1 : group.path1());
      structured.put("path2", path2 != null ? path2 : group.path2());
      structured.put("primaryStatus", group.primaryStatus());
      structured.put("adStrength", group.adStrength());

      return ToolResult.ok(
          text(group, assetGroupId, effectiveStatus, effectiveUrls, newName), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetGroupId, false, e.getMessage());
      throw e;
    }
  }

  // Presence decides whether the field travels, so "" reaches the client and clears the path.
  private String path(JsonNode args, String field) {
    if (!args.has(field)) {
      return null;
    }
    JsonNode node = args.get(field);
    String value = node.isNull() ? "" : node.asText("").trim();
    if (value.length() > GoogleAdsEditSupport.MAX_PATH_LENGTH) {
      throw new McpToolException(
          field
              + " must be at most "
              + GoogleAdsEditSupport.MAX_PATH_LENGTH
              + " characters (got "
              + value.length()
              + ").");
    }
    return value;
  }

  private String text(
      GoogleAssetGroupDetailDto group,
      String assetGroupId,
      String effectiveStatus,
      List<String> effectiveUrls,
      String newName) {
    StringBuilder text = new StringBuilder();
    text.append("Asset group ")
        .append(newName != null ? newName : group.name())
        .append(" (")
        .append(assetGroupId)
        .append(") in campaign ")
        .append(group.campaignName())
        .append(" is now ")
        .append(effectiveStatus)
        .append(", pointing at ")
        .append(String.join(", ", effectiveUrls))
        .append(".\n");
    if ("ENABLED".equals(effectiveStatus)
        && "ENABLED".equals(group.campaignStatus())
        && !"ENABLED".equals(group.status())) {
      // Worth saying out loud: no other tool was involved, and this call just started spend.
      text.append(
          "The campaign is active, so this asset group is eligible to spend its share of the"
              + " campaign budget from now on.");
    }
    return text.toString();
  }
}
