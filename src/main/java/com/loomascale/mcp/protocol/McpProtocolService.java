package com.loomascale.mcp.protocol;

import com.loomascale.mcp.oauth.OAuthAccessTokenService;import com.loomascale.mcp.tool.AdsTool;import com.loomascale.mcp.tool.McpCallOutcome;import com.loomascale.mcp.tool.McpQuotaExceededException;import com.loomascale.mcp.tool.McpToolException;import com.loomascale.mcp.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.mcp.spi.McpToolCallObserver;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.spi.QuotaPolicy;
import com.loomascale.mcp.spi.ToolCallRecord;
import com.loomascale.mcp.oauth.OAuthAccessTokenService.AccessTokenClaims;
import com.loomascale.mcp.oauth.OAuthScopes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Minimal JSON-RPC 2.0 handler for the MCP streamable-HTTP endpoint. Stateless:
// each POST is initialize | notifications/* | tools/list | tools/call | ping.
@Slf4j
@Service
@RequiredArgsConstructor
public class McpProtocolService {

  private static final String PROTOCOL_VERSION = "2025-06-18";
  private static final String SERVER_VERSION = "1.0.0";
  private static final int ERR_METHOD_NOT_FOUND = -32601;
  private static final int ERR_INVALID_PARAMS = -32602;
  private static final int ERR_INTERNAL = -32603;

  private final McpToolRegistry toolRegistry;
  private final ObjectMapper objectMapper;
  private final QuotaPolicy quotaPolicy;
  private final ProductBranding branding;
  private final List<McpToolCallObserver> observers;

  // Returns the JSON-RPC response, or empty for notifications (controller sends 202).
  public Optional<ObjectNode> handle(JsonNode request, AccessTokenClaims claims) {
    String method = request.path("method").asText();
    boolean isNotification = !request.has("id") || request.get("id").isNull();
    JsonNode id = request.get("id");

    if (isNotification) {
      log.debug("MCP notification: {}", method);
      return Optional.empty();
    }

    log.debug("MCP request: {} user={}", method, claims == null ? null : claims.userId());
    try {
      ObjectNode result =
          switch (method) {
            case "initialize" -> initialize(request);
            case "ping" -> objectMapper.createObjectNode();
            case "tools/list" -> toolsList();
            case "tools/call" -> callTool(request, claims);
            default -> throw new JsonRpcException(ERR_METHOD_NOT_FOUND, "Unknown method: " + method);
          };
      return Optional.of(success(id, result));
    } catch (JsonRpcException e) {
      return Optional.of(error(id, e.code, e.getMessage()));
    } catch (Exception e) {
      log.error("MCP handler error for {}: {}", method, e.getMessage());
      return Optional.of(error(id, ERR_INTERNAL, "Internal error"));
    }
  }

  // Logs exactly what the client was handed, so "the tool isn't there" can be
  // settled from the log instead of guessed at.
  private ObjectNode toolsList() {
    ObjectNode payload = toolRegistry.listPayload();
    log.debug("Served {} tools: {}", toolRegistry.names().size(), toolRegistry.names());
    log.debug("tools/list payload: {}", payload);
    return payload;
  }

  private ObjectNode initialize(JsonNode request) {
    ObjectNode result = objectMapper.createObjectNode();
    // Echo the client's protocol version when present, else our default.
    String requested = request.path("params").path("protocolVersion").asText(PROTOCOL_VERSION);
    result.put("protocolVersion", requested.isBlank() ? PROTOCOL_VERSION : requested);
    result.putObject("capabilities").putObject("tools");
    ObjectNode serverInfo = result.putObject("serverInfo");
    serverInfo.put("name", branding.serverName());
    serverInfo.put("version", SERVER_VERSION);
    return result;
  }

  private ObjectNode callTool(JsonNode request, AccessTokenClaims claims) {
    JsonNode params = request.path("params");
    String toolName = params.path("name").asText();
    JsonNode args = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();

    // Field names only — arguments carry model-supplied ad copy, URLs and ids.
    List<String> argFields = new ArrayList<>();
    args.fieldNames().forEachRemaining(argFields::add);
    log.debug("MCP tools/call tool={} args={} user={}", toolName, argFields, claims.userId());

    // Every exit below reports to the admin channel from the finally block, so the
    // notification cannot be skipped by a new branch or an unexpected throw.
    long startedAt = System.currentTimeMillis();
    McpCallOutcome outcome = McpCallOutcome.OK;
    String failure = null;
    try {
      AdsTool tool =
          toolRegistry
              .find(toolName)
              .orElseThrow(
                  () -> new JsonRpcException(ERR_INVALID_PARAMS, "Unknown tool: " + toolName));

      // Scope enforcement — write tools require ads.write. Never trust the model here.
      if (tool.requiresWriteScope() && !claims.scopes().contains(OAuthScopes.ADS_WRITE)) {
        outcome = McpCallOutcome.SCOPE_DENIED;
        failure = "Missing " + OAuthScopes.ADS_WRITE + " scope";
        return toolResult(
            ToolResult.error(
                "This action needs write access. Reconnect "
                    + branding.productName()
                    + " in ChatGPT and grant the \"create and manage campaigns\" permission."));
      }

      // Exempt tools (loomascale_check_quota) neither consume the quota nor are blocked
      // by it, so the balance stays readable exactly when it has run out.
      boolean metered = tool.countsAgainstQuota();
      try {
        // Free-tier quota — the one place billing touches tool dispatch.
        if (metered) {
          quotaPolicy.requireRemaining(claims.userId());
        }
        ToolResult result = tool.execute(claims.userId(), args);
        log.debug(
            "MCP tools/call tool={} metered={} isError={}", toolName, metered, result.isError());
        if (result.isError()) {
          outcome = McpCallOutcome.TOOL_ERROR;
          failure = result.text();
        } else if (metered) {
          quotaPolicy.record(claims.userId(), toolName);
        }
        return toolResult(result);
      } catch (McpQuotaExceededException e) {
        // Free-tier wall, not a broken integration — worth telling apart in the channel.
        log.debug("MCP tools/call tool={} blocked by quota: {}", toolName, e.getMessage());
        outcome = McpCallOutcome.QUOTA_EXHAUSTED;
        failure = e.getMessage();
        return toolResult(ToolResult.error(e.getMessage()));
      } catch (McpToolException e) {
        // Domain failure — safe message, surfaced as an MCP tool error (isError=true).
        log.debug("MCP tools/call tool={} failed: {}", toolName, e.getMessage());
        outcome = McpCallOutcome.TOOL_ERROR;
        failure = e.getMessage();
        return toolResult(ToolResult.error(e.getMessage()));
      }
    } catch (JsonRpcException e) {
      // Rethrown so handle() still answers with the JSON-RPC error; caught only to label it.
      outcome = McpCallOutcome.UNKNOWN_TOOL;
      failure = e.getMessage();
      throw e;
    } finally {
      long durationMs = System.currentTimeMillis() - startedAt;
      log.debug(
          "Publishing MCP tools/call event tool={} outcome={} durationMs={}",
          toolName,
          outcome,
          durationMs);
      notifyObservers(
          new ToolCallRecord(
              claims.userId(),
              claims.clientId(),
              claims.jti(),
              toolName,
              argFields,
              outcome,
              failure,
              durationMs,
              Instant.now()));
    }
  }

  // One failing observer must not turn a successful tool call into a protocol error, and
  // must not stop the others from seeing the record.
  private void notifyObservers(ToolCallRecord record) {
    for (McpToolCallObserver observer : observers) {
      try {
        observer.onToolCall(record);
      } catch (RuntimeException e) {
        log.warn("MCP tool-call observer {} failed: {}", observer.getClass().getSimpleName(), e.getMessage());
      }
    }
  }

  // Builds the tools/call result envelope: content[] + optional structuredContent + isError.
  private ObjectNode toolResult(ToolResult result) {
    ObjectNode node = objectMapper.createObjectNode();
    node.putArray("content").addObject().put("type", "text").put("text", result.text());
    if (result.structured() != null) {
      node.set("structuredContent", objectMapper.valueToTree(result.structured()));
    }
    node.put("isError", result.isError());
    return node;
  }

  private ObjectNode success(JsonNode id, ObjectNode result) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    return response;
  }

  private ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id == null ? objectMapper.nullNode() : id);
    ObjectNode err = response.putObject("error");
    err.put("code", code);
    err.put("message", message);
    return response;
  }

  private static class JsonRpcException extends RuntimeException {
    private final int code;

    JsonRpcException(int code, String message) {
      super(message);
      this.code = code;
    }
  }
}
