# mcp-core

Turn your Java service into an [MCP](https://modelcontextprotocol.io) server that ChatGPT
and Claude can call — with a real OAuth 2.1 authorization server in front of it, not a
shared API key.

Add the dependency, declare a tool, and you have an authenticated `/mcp` endpoint,
dynamic client registration, PKCE, refresh-token rotation, and a consent screen you did
not have to build.

MIT licensed. Extracted from a production Spring Boot service that runs live ad campaigns
from inside ChatGPT.

## The problem

The MCP specification is small. Making a *remote* MCP server that a hosted client will
actually connect to is not, and almost none of the work is protocol work:

| What the client expects | Why it is not obvious |
|---|---|
| `POST /mcp`, one JSON response per request | Stateless streamable HTTP. No SSE, no session id — but `GET /mcp` must still answer `405`, not `404` |
| OAuth 2.1, not an API key | ChatGPT and Claude will not send a static token to a remote server |
| Dynamic client registration (RFC 7591) | The client registers *itself*. You cannot pre-create it, and the endpoint is unauthenticated by design |
| Two discovery documents | RFC 8414 for the authorization server, RFC 9728 for the protected resource. A `401` must name the second one in `WWW-Authenticate` or the client cannot find its way |
| PKCE S256, mandatory | No client secret exists to fall back on |
| Refresh-token rotation | And reuse detection, or a leaked token is valid until it expires |
| Single-use authorization codes | Under concurrency, which means a conditional `UPDATE`, not read-then-write |
| No browser `Origin` | A legitimate client is server-to-server. One that sends `Origin` is a browser reaching your localhost — DNS rebinding |

This library is all of the above, already written and already tested end to end.

## Quick start

```xml
<dependency>
  <groupId>com.loomascale</groupId>
  <artifactId>mcp-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

Declare a tool. It is a `@Component` in your own package — the registry collects every
`AdsTool` bean it can see:

```java
@Component
@RequiredArgsConstructor
public class WhoAmITool implements AdsTool {

  private final ObjectMapper mapper;

  public String name()        { return "who_am_i"; }
  public String description()  { return "Returns the id of the signed-in account."; }
  public ToolAnnotations annotations() { return ToolAnnotations.readOnly(); }
  public boolean requiresWriteScope()  { return false; }

  public ObjectNode inputSchema() { return McpSchemas.object(mapper); }

  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(mapper);
    McpSchemas.prop(schema, "userId", "string", "The signed-in account id");
    return schema;
  }

  public ToolResult execute(String userId, JsonNode args) {
    ObjectNode out = mapper.createObjectNode();
    out.put("userId", userId);          // authenticated for you; never trust args for identity
    return ToolResult.ok("You are " + userId, out);
  }
}
```

Set four environment variables (see [`.env.example`](.env.example) for how to generate
each):

```bash
OAUTH_ISSUER=https://mcp.example.com          # the public origin clients reach you at
OAUTH_ACCESS_TOKEN_SECRET=...                 # openssl rand -base64 48
MCP_TOKEN_ENCRYPTION_KEY=...                  # openssl rand -base64 32
MCP_CONSENT_OPERATOR_PASSWORD=...             # openssl rand -base64 24
```

Include the schema from your Liquibase master changelog:

```xml
<include file="db/changelog/mcp-core/mcp-core-master.xml"/>
<include file="db/changelog/mcp-core/ads-connections.xml"/>  <!-- built-in credential store only -->
```

Start the app. If something required is missing it says so by name, at startup, with the
command to generate it.

## What it does not do

Money and third-party accounts are involved, so the boundaries are worth stating plainly:

- **It does not decide who your users are.** `ResourceOwnerAuthenticator` is yours to
  implement. The default is single-operator: one configured password, one fixed user id.
  That is enough to self-host and is explicitly not a multi-user login.
- **It does not meter anything.** The default `QuotaPolicy` has no allowance. It *does*
  keep a runaway-loop guard, because a model stuck in a loop can otherwise issue hundreds
  of writes in a minute — that is a safety control, not billing.
- **It ships no tools.** The protocol layer, the auth server and the shared ads
  abstractions are here; the tools that talk to an ad platform live in a platform module.
- **It does not terminate TLS.** Put it behind a reverse proxy. The bearer token is a
  header, and MCP clients generally refuse a non-https remote server.

## Connecting a client

Both hosted clients need a public HTTPS URL ending in `/mcp`. They will register
themselves, walk the PKCE flow, and land on the consent screen.

To try it locally, tunnel rather than pointing a client at localhost:

```bash
cloudflared tunnel --url http://localhost:8080
```

Then set `OAUTH_ISSUER` to the tunnel's https origin — clients read the issuer from your
discovery document and talk to whatever it says, so a stale value fails in a way that
looks like a client bug.

To debug without any client at all, drive the handshake by hand:

```bash
curl -s -X POST https://mcp.example.com/mcp \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
```

## Extension points

Every one has a working default and is replaced by declaring a bean of the same type —
that is what `@ConditionalOnMissingBean` is for. There is no `@ComponentScan` in this
library, so nothing is registered that you cannot override.

| Interface | Default | Replace it when |
|---|---|---|
| `AdsTool` | none — you provide these | always |
| `ResourceOwnerAuthenticator` | single operator password | you have real users |
| `AdsConnectionStore` | JDBC + AES-256-GCM | you already store connections |
| `AdsTokenRefresher` | none | your platform's tokens expire |
| `QuotaPolicy` | unlimited + loop guard | you charge for calls |
| `ProductBranding` | configured strings | you want locale-aware links |
| `McpToolCallObserver` | one log line per call | you want an audit trail elsewhere |
| `McpAlertSink` | `log.warn` | you want alerts in chat or email |
| `ConsentPageRenderer` | bundled HTML page | you want it styled |
| `ConsentUrlResolver` | bundled page | you serve your own screen |

## Security

- Access tokens are signed with a key that must be distinct from every other key you use,
  so a token minted for one purpose can never be presented as another.
- Authorization codes and refresh tokens are stored **hashed**. A leaked database row is
  not redeemable.
- Codes are single-use via a conditional `UPDATE`, so two concurrent redemptions cannot
  both mint a token.
- Reusing a rotated refresh token revokes the whole family, in its own transaction so the
  revoke survives the rejection.
- Stored platform credentials are encrypted at rest with AES-256-GCM.
- Requests carrying a browser `Origin` are refused outright.
- Tool arguments are never logged — only argument *names*.

Reporting a vulnerability: see [SECURITY.md](SECURITY.md). Please do not open a public
issue.

## Compatibility

| mcp-core | MCP protocol | Java | Spring Boot |
|---|---|---|---|
| 0.1.x | 2025-06-18 | 21+ | 3.4+ |

While `0.x`, minor versions may change the SPI. It will stabilise at `1.0.0`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Read the secrets section first — this repository
was extracted from a codebase that leaked credentials through placeholder defaults, and
two build gates exist so that cannot happen here.

Built and used in production by [LoomaScale](https://ai.loomascale.com). MIT licensed.
