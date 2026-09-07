package com.loomascale.mcp.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsConnectionStore;
import com.loomascale.mcp.spi.AdsTarget;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

// The test that proves the autoconfiguration is real: boot an application that declares
// nothing but one tool, then drive the whole documented flow over HTTP —
// discovery, dynamic registration, PKCE authorize, consent, token exchange, tools/call.
//
// A unit test of each service would have passed while the wiring was broken, which is the
// failure mode that matters for a library whose entire promise is "add the dependency".
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = McpCoreEndToEndTest.TestApp.class)
@ActiveProfiles("test")
class McpCoreEndToEndTest {

  private static final String OPERATOR_PASSWORD = "correct-horse-battery-staple";

  @SpringBootApplication
  static class TestApp {

    // The only thing an application must provide: a tool, and somewhere credentials live.
    @Bean
    AdsTool echoTool(ObjectMapper mapper) {
      return new EchoTool(mapper);
    }

    @Bean
    AdsConnectionStore connectionStore() {
      return new AdsConnectionStore() {
        @Override
        public Optional<AdsConnection> find(String userId, String platformKey) {
          return Optional.empty();
        }

        @Override
        public AdsConnection save(com.loomascale.mcp.spi.NewAdsConnection connection) {
          throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void selectTarget(String connectionId, String targetId) {}

        @Override
        public String freshAccessToken(AdsConnection connection) {
          return "token";
        }

        @Override
        public List<AdsTarget> targets(AdsConnection connection) {
          return List.of();
        }

        @Override
        public void markExpired(String connectionId) {}

        @Override
        public Optional<String> storedCurrency(AdsConnection connection, String targetId) {
          return Optional.empty();
        }

        @Override
        public void rememberCurrency(AdsConnection c, String targetId, String currency) {}
      };
    }
  }

  static class EchoTool implements AdsTool {
    private final ObjectMapper mapper;

    EchoTool(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "Returns whatever it is given. Exists to prove the wiring.";
    }

    @Override
    public ObjectNode inputSchema() {
      ObjectNode schema = McpSchemas.object(mapper);
      McpSchemas.prop(schema, "value", "string", "Anything");
      return schema;
    }

    @Override
    public ObjectNode outputSchema() {
      ObjectNode schema = McpSchemas.object(mapper);
      McpSchemas.prop(schema, "value", "string", "What was given");
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
      ObjectNode structured = mapper.createObjectNode();
      structured.put("value", args.path("value").asText(""));
      return ToolResult.ok("echoed", structured);
    }
  }

  // Redirect following is off deliberately. The flow's redirects are the thing under
  // test — where authorize sends the browser, and where consent sends it back — and a
  // client that follows them would chase the configured issuer host instead of the
  // random test port, and would replay a form POST against the client's callback.
  private final TestRestTemplate rest =
      new TestRestTemplate(
          new org.springframework.boot.web.client.RestTemplateBuilder()
              .requestFactory(NonFollowingRequestFactory::new));

  static class NonFollowingRequestFactory
      extends org.springframework.http.client.SimpleClientHttpRequestFactory {
    @Override
    protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
        throws java.io.IOException {
      super.prepareConnection(connection, httpMethod);
      connection.setInstanceFollowRedirects(false);
    }
  }

  @LocalServerPort private int port;
  private final ObjectMapper mapper = new ObjectMapper();

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  void discoveryDocumentsDescribeTheServer() {
    JsonNode as = rest.getForObject(url("/.well-known/oauth-authorization-server"), JsonNode.class);
    assertEquals("http://localhost:8080", as.path("issuer").asText());
    assertTrue(as.path("code_challenge_methods_supported").toString().contains("S256"));

    // RFC 9728: how a client discovers WHICH authorization server guards /mcp.
    JsonNode resource =
        rest.getForObject(url("/.well-known/oauth-protected-resource/mcp"), JsonNode.class);
    assertNotNull(resource);
  }

  @Test
  void mcpRejectsAnUnauthenticatedCallAndSaysWhereToAuthenticate() {
    ObjectNode body = mapper.createObjectNode();
    body.put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list");

    ResponseEntity<String> response =
        rest.postForEntity(url("/mcp"), jsonRequest(body, null), String.class);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    String challenge = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
    assertNotNull(challenge, "a 401 must say how to authenticate");
    assertTrue(challenge.contains("resource_metadata"), challenge);
  }

  // A legitimate MCP client is server-to-server and sends no browser Origin. One that does
  // is a browser being used to reach a localhost server, i.e. DNS rebinding.
  // Uses the JDK HTTP client rather than the RestTemplate above: HttpURLConnection
  // silently drops an Origin header it did not set itself, which would make this test
  // pass for the wrong reason (a 401 for a missing token instead of the 403 under test).
  @Test
  void mcpRefusesRequestsCarryingABrowserOrigin() throws Exception {
    ObjectNode body = mapper.createObjectNode();
    body.put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list");

    java.net.http.HttpResponse<String> response =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url("/mcp")))
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://evil.example.com")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

    assertEquals(403, response.statusCode(), "a browser Origin must be refused outright");
  }

  @Test
  void getOnTheMcpEndpointIsNotAllowed() {
    assertEquals(
        HttpStatus.METHOD_NOT_ALLOWED,
        rest.getForEntity(url("/mcp"), String.class).getStatusCode());
  }

  @Test
  void theWholeFlowFromRegistrationToAToolCall() throws Exception {
    // 1. Dynamic client registration (RFC 7591).
    ObjectNode registration = mapper.createObjectNode();
    registration.put("client_name", "Test Client");
    registration.putArray("redirect_uris").add("https://client.example.com/callback");

    JsonNode registered =
        mapper.readTree(
            rest.postForEntity(
                    url("/oauth/register"), jsonRequest(registration, null), String.class)
                .getBody());
    String clientId = registered.path("client_id").asText();
    assertTrue(clientId.startsWith("mcp_"), clientId);

    // 2. Authorize with PKCE. The verifier never leaves the client.
    String verifier = "a".repeat(64);
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8)));

    ResponseEntity<String> authorize =
        rest.getForEntity(
            url(
                "/oauth/authorize?response_type=code&client_id="
                    + clientId
                    + "&redirect_uri=https://client.example.com/callback"
                    + "&code_challenge="
                    + challenge
                    + "&code_challenge_method=S256&scope=ads.read&state=xyz"),
            String.class);
    // Redirected to the consent screen rather than straight back to the client.
    assertEquals(HttpStatus.FOUND, authorize.getStatusCode());
    String consentLocation = authorize.getHeaders().getLocation().toString();
    assertTrue(consentLocation.contains("/oauth/consent?request_id="), consentLocation);
    String requestId = consentLocation.substring(consentLocation.indexOf("request_id=") + 11);

    // 3. The bundled consent page renders, and escapes the client name it was given.
    ResponseEntity<String> page =
        rest.getForEntity(url("/oauth/consent?request_id=" + requestId), String.class);
    assertEquals(HttpStatus.OK, page.getStatusCode());
    assertTrue(page.getBody().contains("Test Client"), "client name should be shown");
    assertTrue(page.getBody().contains("client.example.com"), "redirect host should be shown");

    // 4. Approving without the operator password must not work.
    MultiValueMap<String, String> denied = new LinkedMultiValueMap<>();
    denied.add("decision", "approve");
    denied.add("operator_password", "wrong");
    assertEquals(
        HttpStatus.UNAUTHORIZED,
        rest.postForEntity(
                url("/oauth/consent/" + requestId + "/decide"), formRequest(denied), String.class)
            .getStatusCode());

    // 5. Approve properly; the browser is sent back to the client with a code.
    MultiValueMap<String, String> approve = new LinkedMultiValueMap<>();
    approve.add("decision", "approve");
    approve.add("operator_password", OPERATOR_PASSWORD);
    ResponseEntity<String> approved =
        rest.exchange(
            url("/oauth/consent/" + requestId + "/decide"),
            HttpMethod.POST,
            formRequest(approve),
            String.class);
    assertEquals(HttpStatus.SEE_OTHER, approved.getStatusCode());
    String redirect = approved.getHeaders().getLocation().toString();
    assertTrue(redirect.startsWith("https://client.example.com/callback"), redirect);
    String code = param(redirect, "code");
    assertEquals("xyz", param(redirect, "state"), "state must round-trip unchanged");

    // 6. Exchange the code for tokens, proving possession of the verifier.
    MultiValueMap<String, String> exchange = new LinkedMultiValueMap<>();
    exchange.add("grant_type", "authorization_code");
    exchange.add("code", code);
    exchange.add("client_id", clientId);
    exchange.add("redirect_uri", "https://client.example.com/callback");
    exchange.add("code_verifier", verifier);
    JsonNode tokens =
        mapper.readTree(
            rest.postForEntity(url("/oauth/token"), formRequest(exchange), String.class).getBody());
    String accessToken = tokens.path("access_token").asText();
    assertTrue(accessToken.length() > 20, "expected a signed access token");
    assertEquals("Bearer", tokens.path("token_type").asText());

    // 7. The code is single-use: replaying it must fail, not mint a second token.
    ResponseEntity<String> replay =
        rest.postForEntity(url("/oauth/token"), formRequest(exchange), String.class);
    assertTrue(replay.getStatusCode().isError(), "code replay must be refused");

    // 8. tools/list, authenticated.
    ObjectNode listRequest = mapper.createObjectNode();
    listRequest.put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list");
    JsonNode list =
        mapper.readTree(
            rest.postForEntity(url("/mcp"), jsonRequest(listRequest, accessToken), String.class)
                .getBody());
    assertEquals("echo", list.path("result").path("tools").get(0).path("name").asText());

    // 9. tools/call reaches the application's own tool.
    ObjectNode callRequest = mapper.createObjectNode();
    callRequest.put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call");
    ObjectNode params = callRequest.putObject("params");
    params.put("name", "echo");
    params.putObject("arguments").put("value", "hello");
    JsonNode called =
        mapper.readTree(
            rest.postForEntity(url("/mcp"), jsonRequest(callRequest, accessToken), String.class)
                .getBody());
    assertEquals("hello", called.path("result").path("structuredContent").path("value").asText());
    assertTrue(called.path("result").path("isError").isBoolean());

    // 10. initialize reports the configured server name.
    ObjectNode initRequest = mapper.createObjectNode();
    initRequest.put("jsonrpc", "2.0").put("id", 4).put("method", "initialize");
    JsonNode initialized =
        mapper.readTree(
            rest.postForEntity(url("/mcp"), jsonRequest(initRequest, accessToken), String.class)
                .getBody());
    assertEquals("test-ads", initialized.path("result").path("serverInfo").path("name").asText());
  }

  private HttpEntity<String> jsonRequest(ObjectNode body, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    return new HttpEntity<>(body.toString(), headers);
  }

  private HttpEntity<MultiValueMap<String, String>> formRequest(
      MultiValueMap<String, String> form) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    return new HttpEntity<>(form, headers);
  }

  private static String param(String url, String name) {
    for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv[0].equals(name)) {
        return kv.length > 1 ? kv[1] : "";
      }
    }
    return null;
  }
}
