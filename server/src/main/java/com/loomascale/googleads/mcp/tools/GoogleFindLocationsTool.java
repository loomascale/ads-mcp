package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleGeoTargetSuggestionDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
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

// Place name to geo target constant id — the lookup every location edit needs, because
// Google Ads targets ids, not names, and the same name belongs to several places
// ("Odesa, Ukraine" against "Odessa, Texas"). Read-only: it asks Google what a place is
// called and changes nothing.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleFindLocationsTool implements AdsTool {

  private static final int MAX_QUERIES = 10;

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_find_locations";
  }

  @Override
  public String description() {
    return "Look up Google Ads locations by name and get the geo target constant id of each, with"
        + " its canonical name and target type (Country, Region, City). Use this before changing"
        + " location targeting with google_update_campaign_targeting, or before creating a campaign"
        + " with google_create_campaign, whenever you have a place name rather than an id. Pass"
        + " country_code when a name is ambiguous.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.stringArrayProp(
        schema,
        "query",
        "Place names to look up, e.g. [\"Kyiv\", \"Lviv Oblast\"]. At most " + MAX_QUERIES + ".");
    McpSchemas.prop(
        schema,
        "country_code",
        "string",
        "Two-letter ISO country code to disambiguate the names, e.g. UA. Omit to search worldwide.");
    McpSchemas.prop(
        schema, "locale", "string", "Language of the returned names, e.g. en or uk. Default en.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "query");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    ObjectNode suggestion =
        McpSchemas.objectArrayProp(
            schema, "suggestions", "One row per location Google matched, best match first.");
    McpSchemas.nullableProp(
        suggestion, "searchTerm", "string", "The queried name this row answers.");
    McpSchemas.nullableProp(
        suggestion,
        "id",
        "string",
        "Geo target constant id — what google_update_campaign_targeting and"
            + " google_create_campaign take.");
    McpSchemas.nullableProp(suggestion, "name", "string", "Short name, e.g. Kyiv.");
    McpSchemas.nullableProp(
        suggestion, "canonicalName", "string", "Full name, e.g. Kyiv,Kyiv city,Ukraine.");
    McpSchemas.nullableProp(suggestion, "countryCode", "string", "Country the location sits in.");
    McpSchemas.nullableProp(
        suggestion, "targetType", "string", "How wide the area is, e.g. Country, Region, City.");
    McpSchemas.nullableProp(
        suggestion,
        "status",
        "string",
        "ENABLED locations can be targeted; a REMOVED one no longer can.");
    McpSchemas.required(schema, "suggestions");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    return ToolAnnotations.readOnly();
  }

  @Override
  public boolean requiresWriteScope() {
    return false;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireConnection(userId, AdsPlatforms.GOOGLE_ADS);
    List<String> names = names(args);

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);
    String countryCode =
        args.hasNonNull("country_code") ? args.get("country_code").asText().trim() : null;
    String locale = args.hasNonNull("locale") ? args.get("locale").asText().trim() : "en";

    List<GoogleGeoTargetSuggestionDto> suggestions =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .suggestGeoTargetConstants(token, loginCustomerId, names, countryCode, locale));
    log.debug("google_find_locations user={} names={} hits={}", userId, names, suggestions.size());

    ObjectNode structured = objectMapper.createObjectNode();
    ArrayNode arr = structured.putArray("suggestions");
    StringBuilder text = new StringBuilder();
    for (GoogleGeoTargetSuggestionDto suggestion : suggestions) {
      ObjectNode node = arr.addObject();
      node.put("searchTerm", suggestion.searchTerm());
      node.put("id", suggestion.id());
      node.put("name", suggestion.name());
      node.put("canonicalName", suggestion.canonicalName());
      node.put("countryCode", suggestion.countryCode());
      node.put("targetType", suggestion.targetType());
      node.put("status", suggestion.status());
      text.append("• ")
          .append(
              suggestion.canonicalName() == null ? suggestion.name() : suggestion.canonicalName())
          .append(" — id ")
          .append(suggestion.id())
          .append(suggestion.targetType() == null ? "" : ", " + suggestion.targetType())
          .append(
              suggestion.searchTerm() == null ? "" : " (for \"" + suggestion.searchTerm() + "\")")
          .append("\n");
    }
    if (suggestions.isEmpty()) {
      text.append(
          "Google matched no location for "
              + String.join(", ", names)
              + ". Try a shorter name, or add country_code.");
    }
    return ToolResult.ok(text.toString(), structured);
  }

  private List<String> names(JsonNode args) {
    if (!args.has("query") || !args.get("query").isArray()) {
      throw new McpToolException("query must be an array of place names.");
    }
    List<String> names = new ArrayList<>();
    for (JsonNode entry : args.get("query")) {
      String name = entry.asText("").trim();
      if (!name.isBlank()) {
        names.add(name);
      }
    }
    if (names.isEmpty()) {
      throw new McpToolException("query must contain at least one place name.");
    }
    if (names.size() > MAX_QUERIES) {
      throw new McpToolException("query must contain at most " + MAX_QUERIES + " names.");
    }
    return names;
  }
}
