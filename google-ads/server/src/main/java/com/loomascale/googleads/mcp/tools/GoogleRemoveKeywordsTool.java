package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleKeywordDto;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Deletes keyword criteria for good. The gentler alternative is
// google_update_keyword with status=PAUSED, which keeps the keyword's history; this
// tool is for pruning an ad group down to the terms that actually convert.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleRemoveKeywordsTool implements AdsTool {

  static final int MAX_KEYWORDS = 50;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_remove_keywords";
  }

  @Override
  public String description() {
    return "Permanently remove keywords from a Google Ads ad group. Removal cannot be undone:"
        + " re-adding the same text later creates a fresh criterion with no quality score or"
        + " history, so pause with google_update_keyword when the keyword may come back. Ids that"
        + " are already gone are reported as skipped instead of failing the call. Find criterion"
        + " ids with google_list_keywords. Up to "
        + MAX_KEYWORDS
        + " per call.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "ad_group_id", "string", "Ad group the keywords sit in.");
    McpSchemas.stringArrayProp(
        schema, "criterion_ids", "Criterion ids to remove, at most " + MAX_KEYWORDS + " per call.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "ad_group_id", "criterion_ids");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "adGroupId", "string", "Ad group the keywords were removed from.");
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that ad group belongs to.");
    McpSchemas.prop(schema, "removedCount", "integer", "Number of criteria actually removed.");
    McpSchemas.stringArrayProp(schema, "removedCriterionIds", "Criteria that were removed.");
    McpSchemas.stringArrayProp(
        schema,
        "skippedCriterionIds",
        "Ids that were not in the ad group, e.g. already removed by an earlier call.");
    McpSchemas.stringArrayProp(schema, "removedKeywords", "Texts of the removed keywords.");
    McpSchemas.required(
        schema,
        "adGroupId",
        "campaignId",
        "removedCount",
        "removedCriterionIds",
        "skippedCriterionIds",
        "removedKeywords");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: the criteria are gone along with their history. Idempotent: a
    // repeat call finds nothing left to remove and reports the ids as skipped.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("ad_group_id")) {
      throw new McpToolException("ad_group_id is required.");
    }
    if (!args.has("criterion_ids") || !args.get("criterion_ids").isArray()) {
      throw new McpToolException("criterion_ids must contain at least one entry.");
    }
    Set<String> requested = new LinkedHashSet<>();
    args.get("criterion_ids")
        .forEach(
            node -> {
              String id = node.asText("").trim();
              if (!id.isBlank()) {
                requested.add(id);
              }
            });
    if (requested.isEmpty()) {
      throw new McpToolException("criterion_ids must contain at least one entry.");
    }
    if (requested.size() > MAX_KEYWORDS) {
      throw new McpToolException(
          "criterion_ids must contain at most " + MAX_KEYWORDS + " entries.");
    }
    String adGroupId = args.get("ad_group_id").asText();

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleAdGroupDto adGroup =
        support.requireAdGroup(connection, token, customerId, loginCustomerId, adGroupId);
    Map<String, GoogleKeywordDto> present =
        support.keywordsOf(connection, token, customerId, loginCustomerId, adGroupId).stream()
            .filter(keyword -> keyword.criterionId() != null)
            .collect(
                Collectors.toMap(GoogleKeywordDto::criterionId, Function.identity(), (a, b) -> a));

    List<String> toRemove = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (String criterionId : requested) {
      if (present.containsKey(criterionId)) {
        toRemove.add(criterionId);
      } else {
        skipped.add(criterionId);
      }
    }

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("adGroupId", adGroupId);
    structured.put("campaignId", adGroup.campaignId());
    structured.put("removedCount", toRemove.size());
    ArrayNode removedArr = structured.putArray("removedCriterionIds");
    toRemove.forEach(removedArr::add);
    ArrayNode skippedArr = structured.putArray("skippedCriterionIds");
    skipped.forEach(skippedArr::add);
    ArrayNode textsArr = structured.putArray("removedKeywords");
    toRemove.forEach(id -> textsArr.add(present.get(id).text()));

    if (toRemove.isEmpty()) {
      log.debug("Nothing to remove in ad group {}; {} ids already gone", adGroupId, skipped.size());
      return ToolResult.ok(
          "Nothing removed — none of the "
              + skipped.size()
              + " criterion id(s) are in ad group "
              + adGroup.name()
              + " ("
              + adGroupId
              + "). They may have been removed already.",
          structured);
    }

    String argsSummary = "adGroup=" + adGroupId + ";criteria=" + String.join(",", toRemove);
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .removeKeywords(token, customerId, loginCustomerId, adGroupId, toRemove);
            return null;
          });
      audit.record(userId, name(), WriteKind.DELETE, argsSummary, adGroupId, true, null);
      audit.alert(
          "<b>MCP Ads: google_remove_keywords</b>\nUser: "
              + userId
              + "\nAd group: "
              + adGroup.name()
              + " ("
              + adGroupId
              + ")\nRemoved: "
              + toRemove.size()
              + " keyword(s)");

      StringBuilder text =
          new StringBuilder(
              "Removed "
                  + toRemove.size()
                  + " keyword"
                  + (toRemove.size() == 1 ? "" : "s")
                  + " from ad group "
                  + adGroup.name()
                  + " ("
                  + adGroupId
                  + "):\n");
      for (String criterionId : toRemove) {
        text.append("• ").append(present.get(criterionId).text()).append("\n");
      }
      if (!skipped.isEmpty()) {
        text.append("Skipped ")
            .append(skipped.size())
            .append(" id(s) not in this ad group: ")
            .append(String.join(", ", skipped));
      }
      return ToolResult.ok(text.toString().trim(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.DELETE, argsSummary, adGroupId, false, e.getMessage());
      throw e;
    }
  }
}
