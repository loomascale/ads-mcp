package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleAdAccountDto;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Lists the Google Ads customer accounts the connection can reach, marking
// manager (MCC) accounts, which hold no campaigns of their own.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleListAdAccountsTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_list_ad_accounts";
  }

  @Override
  public String description() {
    return "List the Google Ads accounts you can manage, including accounts reached through a"
        + " manager (MCC) account. Manager accounts hold no campaigns themselves. If you also"
        + " run Meta ads, that server exposes meta_list_ad_accounts.";
  }

  @Override
  public ObjectNode inputSchema() {
    return McpSchemas.object(objectMapper);
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.nullableProp(
        schema, "selectedCustomerId", "string", "Customer account selected on the dashboard.");
    ObjectNode account =
        McpSchemas.objectArrayProp(schema, "accounts", "Google Ads accounts reachable.");
    McpSchemas.prop(account, "id", "string", "Customer id (10 digits, no dashes).");
    McpSchemas.nullableProp(account, "name", "string", "Account descriptive name.");
    McpSchemas.nullableProp(account, "currency", "string", "Account currency code.");
    McpSchemas.prop(account, "manager", "boolean", "True for manager (MCC) accounts.");
    McpSchemas.required(schema, "accounts");
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
    String token = adsService.decryptToken(connection);

    List<String> accessible =
        adsService.call(connection, () -> adsService.client().listAccessibleCustomers(token));
    Map<String, GoogleAdAccountDto> byId = new LinkedHashMap<>();
    for (String accessibleId : accessible) {
      try {
        for (GoogleAdAccountDto account :
            adsService.call(
                connection, () -> adsService.client().listCustomerClients(token, accessibleId))) {
          byId.putIfAbsent(account.id(), account);
        }
      } catch (RuntimeException e) {
        // A suspended accessible customer must not hide the others.
        log.debug("Skipping Google Ads customer {}: {}", accessibleId, e.getMessage());
      }
    }

    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("selectedCustomerId", connection.selectedTargetId());
    var arr = structured.putArray("accounts");
    StringBuilder text = new StringBuilder("Google Ads accounts:\n");
    for (GoogleAdAccountDto account : byId.values()) {
      ObjectNode node = arr.addObject();
      node.put("id", account.id());
      node.put("name", account.name());
      node.put("currency", account.currency());
      node.put("manager", account.manager());
      text.append("• ")
          .append(account.name() == null ? account.id() : account.name())
          .append(account.currency() != null ? " (" + account.currency() + ")" : "")
          .append(account.manager() ? " [manager]" : "")
          .append(" — ")
          .append(account.id())
          .append(account.id().equals(connection.selectedTargetId()) ? " [selected]" : "")
          .append("\n");
    }
    if (byId.isEmpty()) {
      text.append("(no accounts)");
    }
    return ToolResult.ok(text.toString(), structured);
  }
}
