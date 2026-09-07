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
import com.loomascale.googleads.client.dto.GoogleSitelinkSpec;
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

// Creates sitelinks and attaches them where they should serve — account-wide, on one campaign
// or on one ad group.
//
// Two requests, not one: the assets are created first and linked second. That ordering is why
// every check here runs before anything is sent. An Asset cannot be deleted through the Google
// Ads API, so a count Google rejects on the second request would leave the first request's
// assets in the account permanently, and sitelink assets — unlike text and image assets — are
// not deduplicated by content, so a retry makes another copy rather than finding the old one.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCreateSitelinksTool implements AdsTool {

  // An asset is permanent, so this is capped for the same reason the brand asset and media
  // tools are.
  private static final int MAX_SITELINK_CREATES_PER_DAY = 20;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_create_sitelinks";
  }

  @Override
  public String description() {
    return "Add sitelinks — the extra links under a Search ad — and attach them to the whole"
        + " account, to one campaign or to one ad group. Each sitelink needs link text of at most"
        + " "
        + GoogleAdsEditSupport.SITELINK_MAX_LINK_TEXT_LENGTH
        + " characters and an https"
        + " landing page, and may carry two description lines of at most "
        + GoogleAdsEditSupport.SITELINK_MAX_DESCRIPTION_LENGTH
        + " characters each — Google shows"
        + " both lines or neither, so pass both or neither. Google serves at most "
        + GoogleAdsEditSupport.MAX_SITELINKS_PER_LEVEL
        + " sitelinks per level, and this call is"
        + " refused if the new ones would not fit. Calling it twice creates two sets: unlike ad"
        + " copy, Google does not recognise a sitelink it already holds, and an asset can never"
        + " be deleted. Read what is already there with google_list_sitelinks first.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    ObjectNode level =
        McpSchemas.prop(
            schema,
            "level",
            "string",
            "Where the sitelinks serve: account for every campaign in the account, campaign for"
                + " one campaign, ad_group for one ad group. Defaults to campaign.");
    McpSchemas.enumValues(level, List.of("account", "campaign", "ad_group"));
    McpSchemas.prop(
        schema,
        "campaign_id",
        "string",
        "Campaign to attach them to, when level is" + " campaign.");
    McpSchemas.prop(
        schema,
        "ad_group_id",
        "string",
        "Ad group to attach them to, when level is" + " ad_group.");
    ObjectNode item =
        McpSchemas.objectArrayProp(
            schema,
            "sitelinks",
            "Sitelinks to create, at most "
                + GoogleAdsEditSupport.MAX_SITELINKS_PER_CALL
                + " per call.");
    McpSchemas.prop(
        item,
        "link_text",
        "string",
        "The clickable line, at most "
            + GoogleAdsEditSupport.SITELINK_MAX_LINK_TEXT_LENGTH
            + " characters.");
    McpSchemas.prop(item, "final_url", "string", "https page the click lands on.");
    McpSchemas.prop(
        item,
        "description1",
        "string",
        "First description line, at most "
            + GoogleAdsEditSupport.SITELINK_MAX_DESCRIPTION_LENGTH
            + " characters. Requires description2.");
    McpSchemas.prop(
        item,
        "description2",
        "string",
        "Second description line, same limit. Requires description1.");
    McpSchemas.required(item, "link_text", "final_url");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "sitelinks");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "level", "string", "Where the sitelinks were attached.");
    McpSchemas.nullableProp(
        schema,
        "ownerId",
        "string",
        "Campaign or ad group they were attached to. Null at account" + " level.");
    McpSchemas.prop(schema, "createdCount", "integer", "Number of sitelinks created and linked.");
    McpSchemas.prop(schema, "countAfter", "integer", "How many sitelinks the level holds now.");
    ObjectNode sitelink =
        McpSchemas.objectArrayProp(schema, "sitelinks", "One row per created sitelink.");
    McpSchemas.nullableProp(sitelink, "assetId", "string", "Id of the new sitelink asset.");
    McpSchemas.prop(sitelink, "linkText", "string", "The clickable line.");
    McpSchemas.nullableProp(sitelink, "description1", "string", "First description line.");
    McpSchemas.nullableProp(sitelink, "description2", "string", "Second description line.");
    McpSchemas.prop(sitelink, "finalUrl", "string", "Page the click lands on.");
    McpSchemas.required(schema, "level", "createdCount", "countAfter", "sitelinks");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: a new sitelink on a live campaign starts serving immediately, and the asset
    // it creates is permanent — Google offers no way to delete one. Not idempotent: a repeat
    // call creates a second set, because sitelink assets are not deduplicated by content.
    return ToolAnnotations.write(true, false);
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
    if (!args.has("sitelinks")) {
      throw new McpToolException("sitelinks is required.");
    }
    if (audit.countToday(userId, name()) >= MAX_SITELINK_CREATES_PER_DAY) {
      throw new McpToolException(
          "You have already created sitelinks "
              + MAX_SITELINK_CREATES_PER_DAY
              + " times today, which is the daily limit — every call permanently adds"
              + " assets to your Google Ads account and Google cannot delete them.");
    }
    List<GoogleSitelinkSpec> specs =
        support.parseSitelinkSpecs(
            args.get("sitelinks"), "sitelinks", GoogleAdsEditSupport.MAX_SITELINKS_PER_CALL);

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

    // The headroom check reads the level's current links first: Google's ceiling is per level,
    // and the assets created below survive a rejection of the link request that follows them.
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
    support.requireSitelinkHeadroom(level.argument(), existing.size(), specs.size());

    String argsSummary =
        "level=" + level.argument() + ";owner=" + ownerId + ";sitelinks=" + specs.size();
    try {
      List<String> resourceNames =
          adsService.call(
              connection,
              () ->
                  adsService
                      .client()
                      .createSitelinkAssets(token, customerId, loginCustomerId, specs));
      List<GoogleAssetLinkSpec> toLink = new ArrayList<>();
      for (String resourceName : resourceNames) {
        toLink.add(
            new GoogleAssetLinkSpec(
                resourceName, assetId(resourceName), GoogleAdsApiClient.SITELINK_FIELD_TYPE));
      }
      adsService.call(
          connection,
          () -> {
            adsService
                .client()
                .mutateSitelinkLinks(
                    token, customerId, loginCustomerId, level, ownerId, toLink, List.of());
            return null;
          });
      audit.record(userId, name(), WriteKind.CREATE, argsSummary, ownerId, true, null);
      audit.alert(
          "<b>MCP Ads: google_create_sitelinks</b>\nUser: "
              + userId
              + "\nLevel: "
              + level.argument()
              + (ownerId == null ? "" : " (" + ownerId + ")")
              + "\nSitelinks: "
              + specs.size());

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("level", level.argument());
      structured.put("ownerId", ownerId);
      structured.put("createdCount", specs.size());
      structured.put("countAfter", existing.size() + specs.size());
      ArrayNode arr = structured.putArray("sitelinks");
      StringBuilder text =
          new StringBuilder(
              "Added "
                  + specs.size()
                  + " sitelink"
                  + (specs.size() == 1 ? "" : "s")
                  + " at "
                  + level.argument()
                  + " level"
                  + (ownerId == null ? "" : " (" + ownerId + ")")
                  + ":\n");
      for (int i = 0; i < specs.size(); i++) {
        GoogleSitelinkSpec spec = specs.get(i);
        ObjectNode node = arr.addObject();
        node.put("assetId", i < resourceNames.size() ? assetId(resourceNames.get(i)) : null);
        node.put("linkText", spec.linkText());
        node.put("description1", spec.description1());
        node.put("description2", spec.description2());
        node.put("finalUrl", spec.finalUrls().get(0));
        text.append("• \"")
            .append(spec.linkText())
            .append("\" → ")
            .append(spec.finalUrls().get(0))
            .append(
                spec.description1() == null
                    ? ""
                    : " (" + spec.description1() + " / " + spec.description2() + ")")
            .append("\n");
      }
      text.append("The level now holds ")
          .append(existing.size() + specs.size())
          .append(" of the ")
          .append(GoogleAdsEditSupport.MAX_SITELINKS_PER_LEVEL)
          .append(" sitelinks Google serves.");
      return ToolResult.ok(text.toString(), structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.CREATE, argsSummary, ownerId, false, e.getMessage());
      String hint = support.assetErrorHint(e.getMessage());
      throw hint.isEmpty() ? e : new McpToolException(e.getMessage() + " " + hint);
    }
  }

  // Google returns the created resource names in request order; the id is the last segment.
  private String assetId(String resourceName) {
    return resourceName == null ? null : resourceName.substring(resourceName.lastIndexOf('/') + 1);
  }
}
