package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdDto;
import com.loomascale.googleads.client.dto.GoogleAdGroupDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Single on/off switch for Google ad objects — the Google twin of meta_set_status.
// Unlike Meta ids, Google ids are only unique per resource type, so the caller
// names the type. Resuming re-checks the governing campaign budget against the
// user's cap first.
@Component
@RequiredArgsConstructor
public class GoogleSetStatusTool implements AdsTool {

  private static final List<String> OBJECT_TYPES = List.of("CAMPAIGN", "AD_GROUP", "AD");
  private static final List<String> STATUSES = List.of("ENABLED", "PAUSED");

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final BudgetGuardrailService guardrail;
  private final McpWriteAuditService audit;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_set_status";
  }

  @Override
  public String description() {
    return "Pause or resume a Google Ads object: an individual ad, an ad group, or a whole"
        + " campaign. Pausing stops its spend; resuming re-checks the governing campaign budget"
        + " against your cap first. Use google_list_campaigns or google_list_ads to find ids. If"
        + " you also run Meta ads, that server exposes meta_set_status.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    ObjectNode objectType =
        McpSchemas.prop(
            schema, "object_type", "string", "What kind of object object_id refers to.");
    McpSchemas.enumValues(objectType, OBJECT_TYPES);
    McpSchemas.prop(schema, "object_id", "string", "Id of the campaign, ad group, or ad.");
    ObjectNode status =
        McpSchemas.prop(schema, "status", "string", "PAUSED to stop spend, ENABLED to resume it.");
    McpSchemas.enumValues(status, STATUSES);
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "object_type", "object_id", "status");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "objectId", "string", "Id of the object whose status changed.");
    ObjectNode objectType =
        McpSchemas.prop(schema, "objectType", "string", "Kind of object that was changed.");
    McpSchemas.enumValues(objectType, OBJECT_TYPES);
    McpSchemas.nullableProp(schema, "name", "string", "Name of the object.");
    ObjectNode status = McpSchemas.prop(schema, "status", "string", "Status now in effect.");
    McpSchemas.enumValues(status, STATUSES);
    McpSchemas.nullableProp(schema, "currency", "string", McpSchemas.CURRENCY_FIELD_DESCRIPTION);
    McpSchemas.moneyProp(
        schema,
        "dailyBudget",
        "Daily budget of the governing campaign; 0 when it uses a shared budget whose amount is"
            + " not exposed here.");
    McpSchemas.required(schema, "objectId", "objectType", "status", "dailyBudget");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive because the ENABLED branch restarts spend; idempotent because
    // setting a status the object already has is a no-op.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("object_type")
        || !args.hasNonNull("object_id")
        || !args.hasNonNull("status")) {
      throw new McpToolException("object_type, object_id, and status are required.");
    }
    String objectType = args.get("object_type").asText().toUpperCase();
    String objectId = args.get("object_id").asText();
    String status = args.get("status").asText().toUpperCase();
    if (!OBJECT_TYPES.contains(objectType)) {
      throw new McpToolException("object_type must be one of: " + String.join(", ", OBJECT_TYPES));
    }
    if (!STATUSES.contains(status)) {
      throw new McpToolException("status must be one of: " + String.join(", ", STATUSES));
    }

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String currency = adsService.currencyCode(connection, token, customerId, loginCustomerId);

    // Resolve the object and its governing campaign; the lookup doubles as an
    // ownership check against the selected account.
    String name;
    String adGroupIdForAd = null;
    String governingCampaignId;
    switch (objectType) {
      case "CAMPAIGN" -> {
        GoogleCampaignDto campaign =
            findCampaign(connection, token, customerId, loginCustomerId, objectId);
        name = campaign.name();
        governingCampaignId = campaign.id();
      }
      case "AD_GROUP" -> {
        GoogleAdGroupDto adGroup =
            adsService
                .call(
                    connection,
                    () ->
                        adsService.client().listAdGroups(token, customerId, loginCustomerId, null))
                .stream()
                .filter(g -> objectId.equals(g.id()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new McpToolException(
                            "Ad group "
                                + objectId
                                + " was not found in account "
                                + customerId
                                + "."));
        name = adGroup.name();
        governingCampaignId = adGroup.campaignId();
      }
      default -> {
        GoogleAdDto ad =
            adsService
                .call(
                    connection,
                    () ->
                        adsService
                            .client()
                            .listAds(token, customerId, loginCustomerId, null, null, objectId))
                .stream()
                .findFirst()
                .orElseThrow(
                    () ->
                        new McpToolException(
                            "Ad " + objectId + " was not found in account " + customerId + "."));
        name = ad.name();
        adGroupIdForAd = ad.adGroupId();
        governingCampaignId = ad.campaignId();
      }
    }

    // Resuming restarts spend — re-validate the governing campaign budget.
    long governingBudgetCents = 0;
    GoogleCampaignDto governing =
        findCampaign(connection, token, customerId, loginCustomerId, governingCampaignId);
    if (governing.dailyBudgetCents() != null) {
      governingBudgetCents = governing.dailyBudgetCents();
    }
    if ("ENABLED".equals(status)) {
      if (governingBudgetCents <= 0) {
        // A hidden (shared) budget cannot be validated against the cap before spend
        // restarts, so refuse — same stance as the Meta path.
        throw new McpToolException(
            "Could not determine the daily budget governing "
                + objectType.replace('_', ' ').toLowerCase()
                + " "
                + objectId
                + "; resume it in Google Ads directly.");
      }
      guardrail.enforce(connection, governingBudgetCents, currency);
    }

    String argsSummary = "type=" + objectType + ";object=" + objectId + ";status=" + status;
    final String finalAdGroupId = adGroupIdForAd;
    try {
      adsService.call(
          connection,
          () -> {
            switch (objectType) {
              case "CAMPAIGN" ->
                  adsService
                      .client()
                      .setCampaignStatus(token, customerId, loginCustomerId, objectId, status);
              case "AD_GROUP" ->
                  adsService
                      .client()
                      .setAdGroupStatus(token, customerId, loginCustomerId, objectId, status);
              default ->
                  adsService
                      .client()
                      .setAdStatus(
                          token, customerId, loginCustomerId, finalAdGroupId, objectId, status);
            }
            return null;
          });
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, objectId, true, null);
      audit.alert(
          "<b>MCP Ads: google_set_status</b>\nUser: "
              + userId
              + "\nObject: "
              + objectType
              + " "
              + objectId
              + "\nStatus: "
              + status);

      ObjectNode structured = objectMapper.createObjectNode();
      structured.put("objectId", objectId);
      structured.put("objectType", objectType);
      structured.put("name", name);
      structured.put("status", status);
      structured.put("currency", currency);
      structured.put("dailyBudget", Money.majorUnits(governingBudgetCents));
      String label = objectType.replace('_', ' ').toLowerCase();
      String text =
          "PAUSED".equals(status)
              ? "Paused Google " + label + " " + name + " (" + objectId + ")."
              : "Resumed Google "
                  + label
                  + " "
                  + name
                  + " ("
                  + objectId
                  + ")"
                  + (governingBudgetCents > 0
                      ? ", governed by a "
                          + Money.display(governingBudgetCents, currency)
                          + "/day campaign budget."
                      : ".");
      return ToolResult.ok(text, structured);
    } catch (RuntimeException e) {
      audit.record(userId, name(), WriteKind.UPDATE, argsSummary, objectId, false, e.getMessage());
      throw e;
    }
  }

  private GoogleCampaignDto findCampaign(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      String campaignId) {
    return adsService
        .call(
            connection, () -> adsService.client().listCampaigns(token, customerId, loginCustomerId))
        .stream()
        .filter(c -> campaignId.equals(c.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new McpToolException(
                    "Campaign " + campaignId + " was not found in account " + customerId + "."));
  }
}
