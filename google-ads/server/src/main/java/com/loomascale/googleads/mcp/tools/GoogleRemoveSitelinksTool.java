package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.GoogleAdsApiClient;
import com.loomascale.googleads.client.dto.GoogleAssetLinkSpec;
import com.loomascale.googleads.client.dto.GoogleSitelinkDto;
import com.loomascale.googleads.client.dto.GoogleSitelinkLevel;
import com.loomascale.googleads.client.dto.GoogleSitelinkQuery;
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

// Stops a sitelink serving at one level by removing its link.
//
// It unlinks rather than deletes, because Google offers no way to delete an Asset: the sitelink
// stays in the account's asset library and can be linked again later, and unlinking it from one
// campaign leaves every other campaign holding it untouched. That is also why an id that is not
// linked at the named level is reported as skipped rather than as an error — there is nothing
// there to remove, which is the state the caller asked for.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleRemoveSitelinksTool implements AdsTool {

  // Unlinking creates nothing permanent and is reversible by relinking, so the cap is the
  // higher one.
  private static final int MAX_SITELINK_REMOVALS_PER_DAY = 50;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_remove_sitelinks";
  }

  @Override
  public String description() {
    return "Stop sitelinks serving on the whole account, on one campaign or on one ad group. This"
        + " removes the link, not the sitelink: the asset stays in the account and can be linked"
        + " again, and unlinking it from one campaign leaves the other campaigns using it alone."
        + " An asset id that is not linked at the level given is reported as skipped rather than"
        + " failing the call. Get asset ids and their levels from google_list_sitelinks. To"
        + " rewrite a sitelink instead of removing it, use google_update_sitelink — that keeps"
        + " its performance history.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    ObjectNode level =
        McpSchemas.prop(
            schema,
            "level",
            "string",
            "Which link to remove: account, campaign or ad_group. Defaults to campaign.");
    McpSchemas.enumValues(level, List.of("account", "campaign", "ad_group"));
    McpSchemas.prop(
        schema, "campaign_id", "string", "Campaign to unlink from, when level is" + " campaign.");
    McpSchemas.prop(
        schema, "ad_group_id", "string", "Ad group to unlink from, when level is" + " ad_group.");
    McpSchemas.stringArrayProp(
        schema, "asset_ids", "Sitelink asset ids to unlink, from google_list_sitelinks.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "asset_ids");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "level", "string", "Level the links were removed from.");
    McpSchemas.nullableProp(
        schema, "ownerId", "string", "Campaign or ad group unlinked from. Null at account level.");
    McpSchemas.prop(schema, "removedCount", "integer", "Number of links removed.");
    McpSchemas.prop(schema, "countAfter", "integer", "How many sitelinks the level holds now.");
    ObjectNode removed =
        McpSchemas.objectArrayProp(schema, "removed", "One row per link that was removed.");
    McpSchemas.prop(removed, "assetId", "string", "Asset that stopped serving here.");
    McpSchemas.nullableProp(removed, "linkText", "string", "Its link text.");
    McpSchemas.stringArrayProp(
        schema, "skipped", "Asset ids that were not linked at this level, so nothing was done.");
    McpSchemas.required(schema, "level", "removedCount", "countAfter", "removed", "skipped");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: the sitelink stops showing under the ads it was serving with, and its click
    // history at that level ends. Idempotent: a repeat call finds nothing linked and reports
    // every id as skipped.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    String levelArg = args.hasNonNull("level") ? args.get("level").asText() : "campaign";
    GoogleSitelinkLevel level =
        GoogleSitelinkLevel.of(levelArg)
            .orElseThrow(
                () -> new McpToolException("level must be one of: account, campaign, ad_group"));
    if (!args.has("asset_ids")
        || !args.get("asset_ids").isArray()
        || args.get("asset_ids").isEmpty()) {
      throw new McpToolException("asset_ids must contain at least one sitelink asset id.");
    }
    if (args.get("asset_ids").size() > GoogleAdsEditSupport.MAX_SITELINKS_PER_CALL) {
      throw new McpToolException(
          "At most "
              + GoogleAdsEditSupport.MAX_SITELINKS_PER_CALL
              + " asset ids per call (got "
              + args.get("asset_ids").size()
              + ").");
    }
    if (audit.countToday(userId, name()) >= MAX_SITELINK_REMOVALS_PER_DAY) {
      throw new McpToolException(
          "You have already removed sitelinks "
              + MAX_SITELINK_REMOVALS_PER_DAY
              + " times today, which is the daily limit.");
    }
    List<String> assetIds = new ArrayList<>();
    for (JsonNode id : args.get("asset_ids")) {
      String value = id.asText("").trim();
      if (value.isEmpty()) {
        throw new McpToolException("asset_ids contains an empty id.");
      }
      if (!assetIds.contains(value)) {
        assetIds.add(value);
      }
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String campaignId = args.hasNonNull("campaign_id") ? args.get("campaign_id").asText() : null;
    String adGroupId = args.hasNonNull("ad_group_id") ? args.get("ad_group_id").asText() : null;
    String ownerId =
        support.resolveSitelinkOwner(
            connection, token, customerId, loginCustomerId, level, campaignId, adGroupId);

    List<GoogleSitelinkDto> existing =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listSitelinks(
                        token,
                        customerId,
                        loginCustomerId,
                        new GoogleSitelinkQuery(
                            List.of(level),
                            campaignId,
                            adGroupId,
                            null,
                            null,
                            null,
                            GoogleAdsApiClient.MAX_SITELINK_ROWS,
                            false)));

    List<GoogleSitelinkDto> matched = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (String assetId : assetIds) {
      existing.stream()
          .filter(sitelink -> assetId.equals(sitelink.assetId()))
          .findFirst()
          .ifPresentOrElse(matched::add, () -> skipped.add(assetId));
    }

    String argsSummary =
        "level=" + level.argument() + ";owner=" + ownerId + ";assets=" + assetIds.size();
    if (!matched.isEmpty()) {
      List<GoogleAssetLinkSpec> toUnlink = new ArrayList<>();
      for (GoogleSitelinkDto sitelink : matched) {
        toUnlink.add(
            new GoogleAssetLinkSpec(
                sitelink.assetResourceName(),
                sitelink.assetId(),
                GoogleAdsApiClient.SITELINK_FIELD_TYPE));
      }
      try {
        adsService.call(
            connection,
            () -> {
              adsService
                  .client()
                  .mutateSitelinkLinks(
                      token, customerId, loginCustomerId, level, ownerId, List.of(), toUnlink);
              return null;
            });
        audit.record(userId, name(), WriteKind.DELETE, argsSummary, ownerId, true, null);
        audit.alert(
            "<b>MCP Ads: google_remove_sitelinks</b>\nUser: "
                + userId
                + "\nLevel: "
                + level.argument()
                + (ownerId == null ? "" : " (" + ownerId + ")")
                + "\nRemoved: "
                + matched.size());
      } catch (RuntimeException e) {
        audit.record(userId, name(), WriteKind.DELETE, argsSummary, ownerId, false, e.getMessage());
        String hint = support.assetErrorHint(e.getMessage());
        throw hint.isEmpty() ? e : new McpToolException(e.getMessage() + " " + hint);
      }
    }

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("level", level.argument());
    structured.put("ownerId", ownerId);
    structured.put("removedCount", matched.size());
    structured.put("countAfter", existing.size() - matched.size());
    ArrayNode arr = structured.putArray("removed");
    ArrayNode skippedArr = structured.putArray("skipped");
    skipped.forEach(skippedArr::add);
    StringBuilder text =
        new StringBuilder(
            "Removed "
                + matched.size()
                + " sitelink"
                + (matched.size() == 1 ? "" : "s")
                + " from "
                + level.argument()
                + " level"
                + (ownerId == null ? "" : " (" + ownerId + ")")
                + ":\n");
    for (GoogleSitelinkDto sitelink : matched) {
      ObjectNode node = arr.addObject();
      node.put("assetId", sitelink.assetId());
      node.put("linkText", sitelink.linkText());
      text.append("• \"")
          .append(sitelink.linkText())
          .append("\" (asset ")
          .append(sitelink.assetId())
          .append(") — the asset stays in the account and can be linked again\n");
    }
    if (!skipped.isEmpty()) {
      text.append("Not linked here, so nothing to remove: ")
          .append(String.join(", ", skipped))
          .append("\n");
    }
    text.append("The level now holds ")
        .append(existing.size() - matched.size())
        .append(" sitelink")
        .append(existing.size() - matched.size() == 1 ? "" : "s")
        .append(".");
    return ToolResult.ok(text.toString(), structured);
  }
}
