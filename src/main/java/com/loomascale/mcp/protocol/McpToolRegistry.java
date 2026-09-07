package com.loomascale.mcp.protocol;

import com.loomascale.mcp.tool.AdsTool;import com.loomascale.mcp.tool.ToolAnnotations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Collects all AdsTool beans and builds the tools/list payload. Mirrors
// SocialProviderRegistry: adding a tool = adding a @Component, nothing else.
@Slf4j
@Component
public class McpToolRegistry {

  private final Map<String, AdsTool> tools;
  private final ObjectMapper objectMapper;

  public McpToolRegistry(List<AdsTool> toolBeans, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.tools =
        toolBeans.stream().collect(Collectors.toMap(AdsTool::name, t -> t, (a, b) -> a));
    log.info("Registered {} MCP ads tools: {}", tools.size(), tools.keySet());
  }

  public Optional<AdsTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public Set<String> names() {
    return tools.keySet();
  }

  // { tools: [ { name, description, inputSchema, outputSchema, annotations } ] }
  public ObjectNode listPayload() {
    ObjectNode root = objectMapper.createObjectNode();
    ArrayNode arr = root.putArray("tools");
    for (AdsTool tool : tools.values()) {
      ObjectNode t = arr.addObject();
      t.put("name", tool.name());
      t.put("description", tool.description());
      t.set("inputSchema", tool.inputSchema());
      t.set("outputSchema", tool.outputSchema());
      ToolAnnotations a = tool.annotations();
      ObjectNode ann = t.putObject("annotations");
      ann.put("readOnlyHint", a.readOnlyHint());
      ann.put("destructiveHint", a.destructiveHint());
      ann.put("idempotentHint", a.idempotentHint());
      ann.put("openWorldHint", a.openWorldHint());
    }
    return root;
  }
}
