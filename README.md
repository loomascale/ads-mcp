# ads-mcp

Self-hostable MCP servers that let ChatGPT or Claude run real ad accounts — with spend
guardrails they cannot talk their way past.

MIT licensed. Extracted from a production service that has run live campaigns from inside
ChatGPT since 2026.

## Servers

| | What it does | Docs |
|---|---|---|
| **[google-ads-mcp](google-ads/README.md)** | 42 tools over the Google Ads API: performance, keyword research, campaign and Performance Max creation, budget and bidding changes | [README](google-ads/README.md) |

Meta Ads is next.

## Run one

No JDK, no Maven, no source build:

```bash
curl -O https://raw.githubusercontent.com/loomascale/ads-mcp/main/docker-compose.yml
curl -O https://raw.githubusercontent.com/loomascale/ads-mcp/main/.env.example
cp .env.example .env && $EDITOR .env      # every value documented in place
docker compose up
```

Then open `http://localhost:8080/connections` and connect an ad account.

Set `MCP_DEFAULT_MAX_DAILY_BUDGET_CENTS` low before pointing this at a live account. It is
the last thing between a model and your card.

Full prerequisites — the Google Ads developer token in particular, which needs an
application and takes days to approve — are in the
[server README](google-ads/README.md#prerequisites).

## What these will not do

Worth reading before running anything, because money is involved:

- **Nothing goes live by itself.** Campaign creation has no status field in its tool
  schema, so a model *cannot* create an enabled campaign. Activation is a separate tool.
- **Only campaigns this server created can be activated** through it. One made in the ad
  platform's own UI is refused, because its budget never passed the cap check.
- **Every budget change is checked against a cap** you set, in the account's own currency.
  The server refuses; it never silently clamps.
- **Per-tool daily limits** and a **runaway-loop guard**, so a stuck model cannot make
  hundreds of changes.
- **Single-operator by default.** One password, one account. Not a multi-user login.

## Build it as a library

[`mcp-core`](mcp-core/README.md) is the reusable half: MCP's JSON-RPC transport, a tool
registry SPI, and a minimal OAuth 2.1 authorization server with dynamic client
registration, PKCE and refresh-token rotation. If you want ChatGPT or Claude to call *your*
tools over an authenticated endpoint, that is the part to depend on — it knows nothing
about advertising beyond a few shared abstractions the platform servers reuse.

## Repository layout

One repository, one version. A change spanning the core and a platform is one commit and
one build, which is why this is not three repositories:

```
mcp-core/            protocol, OAuth 2.1 server, shared ads abstractions
google-ads/client/   Google Ads API client — no Spring, no MCP, usable on its own
google-ads/server/   the runnable MCP server and its tools
```

`google-ads/server` resolves `mcp-core` at `${project.version}` inside the reactor, so a
fresh clone builds with nothing published anywhere:

```bash
./mvnw verify
```

## Security

Credentials are encrypted at rest with AES-256-GCM. Authorization codes and refresh tokens
are stored hashed and are single-use; reusing a rotated refresh token revokes the whole
family. Model-supplied image URLs pass an SSRF policy before anything is fetched. Tool
arguments are never logged — only argument names.

Put TLS in front of any of this. Report vulnerabilities privately: see
[SECURITY.md](SECURITY.md).

## Who builds this

These servers are extracted from **[LoomaScale](https://ai.loomascale.com)**, an AI
operator for Google Ads: you talk to ChatGPT or Claude, and it reads your account,
finds waste, and makes the changes. This repository is the engine that runs it — not a
demo or a cut-down version. The tools here are the same ones that have created and managed
live campaigns since 2026, guardrails included.

What stays in the product rather than here is the commercial half: accounts and billing,
usage metering, the multi-user dashboard, and the connect flows for people who do not want
to run a server. `mcp-core` exposes those as replaceable interfaces — `QuotaPolicy`,
`ResourceOwnerAuthenticator`, `AdsConnectionStore` — precisely so the open-source build has
working defaults and LoomaScale can substitute its own.

## Would you rather not run a server?

[**ai.loomascale.com**](https://ai.loomascale.com) is the hosted version. Same tools, no
Docker, no developer token application, no OAuth client to register. It starts with a
[free Google Ads waste audit](https://ai.loomascale.com/google-ads-waste-audit) — the
assistant reads your account and reports what is being wasted, before you pay anything.
Plans and limits are on the [pricing page](https://ai.loomascale.com/pricing).

Self-hosting is a genuine alternative, not a teaser: everything needed to run these servers
against your own accounts is in this repository under the MIT licence, with no feature held
back and no phone-home.

## Useful even if you self-host

The connection guides are written for the hosted product but the client-side steps are
identical against your own server — the same consent screen, the same connector setup:

- [Connect ChatGPT to Google Ads](https://ai.loomascale.com/connect-chatgpt-to-google-ads)
- [Connect Claude to Google Ads](https://ai.loomascale.com/connect-claude-to-google-ads)
- [Running Google Ads from ChatGPT](https://ai.loomascale.com/google-ads-chatgpt) — walkthrough video
- [What the assistant actually looks for](https://ai.loomascale.com/google-ads-waste-audit)

Other things extracted from the same codebase live at
[ai.loomascale.com/oss](https://ai.loomascale.com/oss/ad-events).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — the secrets section first.

Built and used in production by [LoomaScale](https://ai.loomascale.com). MIT licensed.
