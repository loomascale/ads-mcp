package com.loomascale.googleads.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.loomascale.mcp.protocol.McpToolRegistry;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsPlatformDescriptor;
import com.loomascale.mcp.spi.AdsTokenRefresher;
import com.loomascale.mcp.tool.AdsTool;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

// Boots the real application against H2.
//
// The failure this exists to catch is silent: if component scanning does not reach the
// tools, McpToolRegistry simply reports zero and tools/list returns an empty array. Every
// unit test still passes, the server starts cleanly, and the model is told this server can
// do nothing. So the tool COUNT is asserted, not merely that the context loads.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GoogleAdsMcpBootTest {

  @Autowired private McpToolRegistry registry;
  @Autowired private List<AdsTool> tools;
  @Autowired private List<AdsPlatformDescriptor> descriptors;
  @Autowired private List<AdsTokenRefresher> refreshers;
  @Autowired private AdsConnectionStore connectionStore;
  @Autowired private TestRestTemplate rest;

  @Test
  void everyGoogleToolIsRegistered() {
    assertTrue(tools.size() >= 40, "expected the full Google tool set, found " + tools.size());
    assertEquals(tools.size(), registry.names().size(), "every tool bean should be registered");
  }

  // A duplicate name would silently shadow a tool in the registry, and the model would
  // never learn the other one exists.
  @Test
  void toolNamesAreUniqueAndWellFormed() {
    Set<String> names = registry.names().stream().collect(Collectors.toSet());
    assertEquals(registry.names().size(), names.size(), "tool names must be unique");
    for (String name : names) {
      assertTrue(name.matches("^[a-z][a-z0-9_]*$"), "not a valid MCP tool name: " + name);
      assertTrue(name.startsWith("google_"), "expected a google_ prefix: " + name);
    }
  }

  // Declaring an outputSchema is a promise that execute() honours it, so `required` may
  // only name fields the schema actually defines.
  @Test
  void everyToolDeclaresAUsableOutputSchema() {
    for (AdsTool tool : tools) {
      JsonNode schema = tool.outputSchema();
      assertNotNull(schema, tool.name() + " has no outputSchema");
      assertEquals("object", schema.path("type").asText(), tool.name() + " outputSchema type");
      JsonNode properties = schema.path("properties");
      assertFalse(properties.isMissingNode(), tool.name() + " declares no properties");
      for (JsonNode required : schema.path("required")) {
        assertTrue(
            properties.has(required.asText()),
            tool.name() + " requires '" + required.asText() + "' but never declares it");
      }
    }
  }

  @Test
  void annotationsAreHonestAboutWriting() {
    for (AdsTool tool : tools) {
      assertNotNull(tool.annotations(), tool.name() + " has no annotations");
      if (tool.annotations().readOnlyHint()) {
        // A client uses these to decide whether to prompt before acting. A read-only tool
        // that is also destructive, or that demands write scope, is a contradiction that
        // would either over-prompt or under-prompt.
        assertFalse(
            tool.annotations().destructiveHint(), tool.name() + " is readOnly AND destructive");
        assertFalse(tool.requiresWriteScope(), tool.name() + " is readOnly but wants write scope");
      }
    }
  }

  @Test
  void theGooglePlatformIsWiredForBothTheGateAndTokenRefresh() {
    assertTrue(
        descriptors.stream().anyMatch(d -> "google_ads".equals(d.platformKey())),
        "the auth gate has no Google descriptor, so every tool would fail its gate");
    assertTrue(
        refreshers.stream().anyMatch(r -> "google_ads".equals(r.platformKey())),
        "no Google token refresher, so connections would expire and never renew");
  }

  @Test
  void theBuiltInCredentialStoreIsActiveAndItsSchemaExists() {
    // Reaching the store proves the table exists: the query runs against H2 with the
    // bundled changelog applied.
    assertTrue(connectionStore.find("nobody", "google_ads").isEmpty());
  }

  @Test
  void theConnectionsPageAsksForThePasswordRatherThanLeakingState() {
    ResponseEntity<String> response = rest.getForEntity("/connections", String.class);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertTrue(response.getBody().contains("Operator password"), response.getBody());
  }

  @Test
  void mcpAndOAuthEndpointsCameFromTheCoreDependency() {
    assertEquals(
        HttpStatus.OK,
        rest.getForEntity("/.well-known/oauth-authorization-server", String.class).getStatusCode());
    assertEquals(
        HttpStatus.METHOD_NOT_ALLOWED, rest.getForEntity("/mcp", String.class).getStatusCode());
  }
}
