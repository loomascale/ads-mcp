# google-ads-mcp

Let ChatGPT or Claude run your Google Ads accounts — read performance, research keywords,
build campaigns, edit budgets — on a server you host, with spend guardrails it cannot talk
its way past.

42 tools over an authenticated MCP endpoint. MIT licensed. Extracted from a production
service that has run live campaigns from inside ChatGPT since 2026.

## The problem

Pointing an LLM at the Google Ads API sounds like a weekend project. The parts that make it
not:

| Sharp edge | What bites you |
|---|---|
| **Developer token access levels** | A new token is *Test Account* access and can only reach test accounts. Your first run returns empty lists and looks broken. Basic access needs an application and takes days |
| **Manager accounts** | `listAccessibleCustomers` mostly returns MCC accounts, which hold no campaigns. The accounts that run ads are their children, each addressed with `login-customer-id` set to the manager it was found under |
| **Micros** | The API speaks millionths. A budget of `5000000` is 5 units of currency, and getting this wrong by 10⁶ is a live spend incident |
| **Currency** | Amounts carry no currency. Report a UAH budget with a `$` and the user reads 40× the real number |
| **GAQL** | Not SQL. No `JOIN`, segmentation changes what a row *means*, and asking for the wrong segment silently multiplies your totals |
| **API versions** | Retired on a published schedule. A pinned version stops working, not deprecates |
| **An LLM will try to spend money** | It will propose activating a campaign, raising a budget, or "just testing" with a real one |

This server handles all of it, and refuses the last one by default.

## What it will not do

Read this before the quick start — money is involved.

- **Nothing goes live by itself.** `google_create_campaign` has no status field in its
  schema, so the model *cannot* create an enabled campaign. Activation is a separate tool.
- **Only campaigns this server created can be activated** through it. One created in the
  Google Ads UI is refused, because its budget never passed the cap check.
- **Every budget change is checked against a cap** you set, in the account's own currency.
  The server refuses; it never silently clamps.
- **Per-tool daily limits** on the write tools, so a loop cannot make hundreds of changes.
- **A runaway-loop guard** independent of that: a burst of calls in one window is refused.
- **It does not decide who your users are.** Out of the box it is single-operator: one
  password, one account. Not a multi-user login.

## Quick start

```bash
git clone https://github.com/loomascale/ads-mcp && cd ads-mcp
cp .env.example .env && $EDITOR .env      # every value is documented in place
docker compose up
```

Then open `http://localhost:8080/connections`, enter your operator password, and connect a
Google Ads account.

Start `MCP_DEFAULT_MAX_DAILY_BUDGET_CENTS` low. It is the last thing between a model and
your card.

## Prerequisites

**A Google Ads developer token.** Google Ads manager (MCC) account → Tools & Settings →
API Center. Apply for **Basic** access; the default is Test Account access, which reaches
test accounts only and is the most common reason a first run looks broken. Approval takes
days — start here.

**A Google Cloud OAuth client.** Enable the *Google Ads API* on the project, then
Credentials → OAuth client ID → Web application. Register
`http://localhost:8080/connect/google/callback` as an authorised redirect URI; Google
refuses with `redirect_uri_mismatch` if it differs at all.

**A manager account?** Nothing to configure. The connect flow walks each accessible
customer's tree, skips managers, and records the right `login-customer-id` per account.

## Connecting a client

ChatGPT and Claude both need a public **HTTPS** URL ending in `/mcp`. They register
themselves, walk PKCE, and land on the consent screen.

Neither will connect to `localhost`, so tunnel rather than trying:

```bash
cloudflared tunnel --url http://localhost:8080
```

Set `OAUTH_ISSUER` to the tunnel's https origin. Clients read the issuer from your
discovery document and then talk to whatever it says, so a stale value fails in a way that
looks like a client bug.

To debug with no client at all:

```bash
curl -s -X POST https://your-host/mcp \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

## The tools

42, all prefixed `google_`. Read tools need `ads.read`; write tools need `ads.write` *and*
pass the guardrails above.

**Accounts and billing** — `list_ad_accounts`, `check_billing`, `check_pacing`,
`update_account_budget`

**Campaigns** — `list_campaigns`, `create_campaign`, `create_pmax_campaign`,
`activate_campaign`, `set_status`, `update_budget`, `update_campaign_bidding`,
`update_campaign_settings`, `update_campaign_targeting`, `update_campaign_negatives`,
`update_campaign_conversion_goals`

**Ad groups and ads** — `create_ad_group`, `update_ad_group`, `list_ads`, `create_ad`,
`update_ad`

**Keywords and search terms** — `list_keywords`, `add_keywords`, `remove_keywords`,
`update_keyword`, `list_search_terms`, `keyword_ideas`

**Performance Max** — `list_asset_groups`, `update_asset_group`,
`update_asset_group_assets`, `update_asset_group_media`, `update_search_themes`,
`update_brand_assets`

**Sitelinks** — `list_sitelinks`, `create_sitelinks`, `update_sitelink`, `remove_sitelinks`

**Insights** — `get_insights`, `get_geo_insights`, `get_audience_insights`,
`list_conversion_actions`, `update_conversion_action`, `find_locations`

## Using it as a library

The API client is published separately, with no Spring and no MCP, for code that just needs
to talk to Google Ads:

```xml
<dependency>
  <groupId>com.loomascale</groupId>
  <artifactId>google-ads-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

The tools and server are `google-ads-mcp`, built on
[mcp-core](../mcp-core/README.md), which supplies the protocol layer and
the OAuth 2.1 authorization server. Every extension point there — credentials, quota,
branding, consent, audit — is replaceable by declaring a bean.

## Security

Credentials are encrypted at rest with AES-256-GCM. Access tokens are signed with a key
that must be distinct from anything else you sign with. Authorization codes and refresh
tokens are stored hashed and are single-use, with reuse revoking the whole token family.
Image URLs handed in by the model go through an SSRF policy before the server fetches
anything. Tool arguments are never logged — only argument names.

Put TLS in front of it. Report vulnerabilities privately: see [SECURITY.md](SECURITY.md).

## Compatibility

| google-ads-mcp | mcp-core | Google Ads API | Java |
|---|---|---|---|
| 0.1.x | 0.1.x | v21 | 21+ |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) — the secrets section first.

Built and used in production by [LoomaScale](https://ai.loomascale.com). MIT licensed.
