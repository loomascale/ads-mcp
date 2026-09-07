package com.loomascale.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import org.junit.jupiter.api.Test;

// The README's quick-start tool, compiled and executed.
//
// A README example that does not compile is worse than no example: it is the first thing
// a prospective user tries, and it fails in a way that looks like the library is broken.
// This test is the reason the snippet can be trusted — if you edit one, edit both.
class ReadmeExampleTest {

  static class WhoAmITool implements AdsTool {

    private final ObjectMapper mapper;

    WhoAmITool(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    public String name() {
      return "who_am_i";
    }

    public String description() {
      return "Returns the id of the signed-in account.";
    }

    public ToolAnnotations annotations() {
      return ToolAnnotations.readOnly();
    }

    public boolean requiresWriteScope() {
      return false;
    }

    public ObjectNode inputSchema() {
      return McpSchemas.object(mapper);
    }

    public ObjectNode outputSchema() {
      ObjectNode schema = McpSchemas.object(mapper);
      McpSchemas.prop(schema, "userId", "string", "The signed-in account id");
      return schema;
    }

    public ToolResult execute(String userId, JsonNode args) {
      ObjectNode out = mapper.createObjectNode();
      out.put("userId", userId);
      return ToolResult.ok("You are " + userId, out);
    }
  }

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void theReadmeExampleWorks() {
    WhoAmITool tool = new WhoAmITool(mapper);

    ToolResult result = tool.execute("user-1", mapper.createObjectNode());

    assertFalse(result.isError());
    assertEquals("You are user-1", result.text());
    assertEquals("user-1", mapper.valueToTree(result.structured()).path("userId").asText());
  }

  // The claim the README makes about identity: userId is supplied by the server, and a
  // tool must never take it from arguments.
  @Test
  void identityComesFromTheServerNotFromArguments() {
    WhoAmITool tool = new WhoAmITool(mapper);
    ObjectNode args = mapper.createObjectNode();
    args.put("userId", "somebody-else");

    ToolResult result = tool.execute("user-1", args);

    assertEquals("You are user-1", result.text());
  }

  @Test
  void readOnlyAnnotationsAreConsistent() {
    ToolAnnotations annotations = new WhoAmITool(mapper).annotations();

    assertTrue(annotations.readOnlyHint());
    assertFalse(annotations.destructiveHint(), "a read-only tool cannot be destructive");
  }
}
