package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleSitelinkDto;
import com.loomascale.googleads.client.dto.GoogleSitelinkLevel;
import com.loomascale.googleads.client.dto.GoogleSitelinkUpdateSpec;
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

// Rewrites one sitelink in place.
//
// A sitelink asset is editable, unlike the text assets behind ad copy, which is what lets a
// fixed landing page or a reworded link keep the sitelink's id, its links and its performance
// history instead of starting a new one from zero.
//
// The asset is account-scoped, though, and the same one can be linked to several campaigns and
// ad groups at once. Editing it changes the sitelink in every one of them, so the tool reads
// back where the asset is linked before it mutates and names all of them in the result.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateSitelinkTool implements AdsTool {

  // Higher than the create cap: an edit creates nothing permanent, it only rewrites what is
  // already there.
  private static final int MAX_SITELINK_EDITS_PER_DAY = 50;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_sitelink";
  }

  @Override
  public String description() {
    return "Rewrite an existing Google Ads sitelink: its link text, its two description lines or"
        + " the page it sends people to. Editing keeps the sitelink's id and its performance"
        + " history, which is why this is the right way to fix a dead landing page or reword a"
        + " link rather than removing it and creating a replacement. A sitelink is one shared"
        + " asset: if it is linked to several campaigns or ad groups, this changes it in all of"
        + " them, and the result lists everywhere it is used — tell the user before calling."
        + " Get asset ids from google_list_sitelinks. Pass only the fields you are changing.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(
        schema, "asset_id", "string", "Sitelink asset to rewrite, from google_list_sitelinks.");
    McpSchemas.prop(
        schema,
        "link_text",
        "string",
        "New clickable line, at most "
            + GoogleAdsEditSupport.SITELINK_MAX_LINK_TEXT_LENGTH
            + " characters.");
    McpSchemas.prop(
        schema,
        "description1",
        "string",
        "New first description line, at most "
            + GoogleAdsEditSupport.SITELINK_MAX_DESCRIPTION_LENGTH
            + " characters.");
    McpSchemas.prop(schema, "description2", "string", "New second description line, same limit.");
    McpSchemas.prop(schema, "final_url", "string", "New https page the click lands on.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "asset_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "assetId", "string", "Sitelink asset that was rewritten.");
    McpSchemas.stringArrayProp(schema, "changed", "Fields this call changed.");
    ObjectNode link =
        McpSchemas.objectArrayProp(
            schema,
            "linkedTo",
            "Everywhere this sitelink is linked, and therefore everywhere the edit lands.");
    McpSchemas.prop(link, "level", "string", "account, campaign or ad_group.");
    McpSchemas.nullableProp(
        link, "ownerId", "string", "Campaign or ad group id, null at account" + " level.");
    McpSchemas.nullableProp(link, "ownerName", "string", "Name of that campaign or ad group.");
    McpSchemas.nullableProp(schema, "linkText", "string", "Link text after the edit.");
    McpSchemas.nullableProp(schema, "description1", "string", "First line after the edit.");
    McpSchemas.nullableProp(schema, "description2", "string", "Second line after the edit.");
    McpSchemas.stringArrayProp(schema, "finalUrls", "Landing pages after the edit.");
    McpSchemas.required(schema, "assetId", "changed", "linkedTo");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: the previous text and landing page are overwritten everywhere the sitelink
    // serves, and Google keeps no copy. Idempotent: the values are absolute, so repeating the
    // same call writes the same sitelink.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("asset_id")) {
      throw new McpToolException("asset_id is required.");
    }
    String assetId = args.get("asset_id").asText();
    if (audit.countToday(userId, name()) >= MAX_SITELINK_EDITS_PER_DAY) {
      throw new McpToolException(
          "You have already edited sitelinks "
              + MAX_SITELINK_EDITS_PER_DAY
              + " times today, which is the daily limit.");
    }
    GoogleSitelinkUpdateSpec spec = support.parseSitelinkUpdateSpec(args);

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    // Where the asset is linked, read before the mutate: it is both the ownership check — an
    // asset nobody in this account links is not this account's to edit — and the blast radius
    // the caller has to be told about.
    List<GoogleSitelinkDto> links = new ArrayList<>();
    for (GoogleSitelinkLevel level : GoogleSitelinkLevel.values()) {
      links.addAll(
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .listSitelinkLinks(token, customerId, loginCustomerId, level, assetId)));
    }
    if (links.isEmpty()) {
      throw new McpToolException(
          "Sitelink asset "
              + assetId
              + " is not linked anywhere in account "
              + customerId
              + ". Use google_list_sitelinks to see the sitelinks this account serves.");
    }
    GoogleSitelinkDto current = links.get(0);
    // Google shows both description lines or neither, so setting one on a sitelink that has no
    // other line is a rejection waiting to happen. Adding the second line to a sitelink that
    // already has one is fine, which is why this reads the current values rather than refusing
    // a lone description outright.
    support.requireSitelinkDescriptions(
        current.linkText(),
        spec.description1() != null ? spec.description1() : current.description1(),
        spec.description2() != null ? spec.description2() : current.description2());
    String argsSummary = "asset=" + assetId + ";links=" + links.size();
    try {
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .updateSitelinkAsset(
                    token, customerId, loginCustomerId, current.assetResourceName(), spec);
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetId, true, null);
      audit.alert(
          "<b>MCP Ads: google_update_sitelink</b>\nUser: "
              + userId
              + "\nAsset: "
              + assetId
              + "\nLinked in: "
              + links.size()
              + " place(s)");

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("assetId", assetId);
      ArrayNode changed = structured.putArray("changed");
      if (spec.linkText() != null) {
        changed.add("link_text");
      }
      if (spec.description1() != null) {
        changed.add("description1");
      }
      if (spec.description2() != null) {
        changed.add("description2");
      }
      if (spec.finalUrls() != null) {
        changed.add("final_url");
      }
      structured.put("linkText", spec.linkText() != null ? spec.linkText() : current.linkText());
      structured.put(
          "description1",
          spec.description1() != null ? spec.description1() : current.description1());
      structured.put(
          "description2",
          spec.description2() != null ? spec.description2() : current.description2());
      ArrayNode urls = structured.putArray("finalUrls");
      (spec.finalUrls() != null ? spec.finalUrls() : current.finalUrls()).forEach(urls::add);
      ArrayNode linkArr = structured.putArray("linkedTo");
      StringBuilder text =
          new StringBuilder("Rewrote sitelink asset " + assetId + " in account " + customerId);
      text.append(links.size() > 1 ? ". It serves in " + links.size() + " places:\n" : ":\n");
      for (GoogleSitelinkDto link : links) {
        ObjectNode node = linkArr.addObject();
        node.put("level", link.level().argument());
        node.put("ownerId", link.ownerId());
        node.put("ownerName", link.ownerName());
        text.append("• ")
            .append(link.level().argument())
            .append(link.ownerName() == null ? "" : " " + link.ownerName())
            .append(link.ownerId() == null ? "" : " (" + link.ownerId() + ")")
            .append("\n");
      }
      if (spec.linkText() != null) {
        text.append("Link text: \"").append(spec.linkText()).append("\"\n");
      }
      if (spec.description1() != null) {
        text.append("Descriptions: ")
            .append(spec.description1())
            .append(" / ")
            .append(spec.description2())
            .append("\n");
      }
      if (spec.finalUrls() != null) {
        text.append("Lands on: ").append(spec.finalUrls().get(0)).append("\n");
      }
      return ToolResult.ok(text.toString().trim(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, assetId, false, e.getMessage());
      String hint = support.assetErrorHint(e.getMessage());
      throw hint.isEmpty() ? e : new McpToolException(e.getMessage() + " " + hint);
    }
  }
}
