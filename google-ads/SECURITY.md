# Security Policy

## Supported versions

The latest minor release receives security fixes. While in `0.x`, that means the latest
`0.x` only.

## Reporting a vulnerability

**Please do not open a public issue.** Report privately, either way:

- GitHub → **Security** → **Report a vulnerability** (preferred — it keeps the thread with
  the repository and lets us issue an advisory)
- Email **security@loomascale.com**

Include the affected version, reproduction steps, and impact. We aim to acknowledge within
three business days and to ship a fix or mitigation within thirty days, coordinating
disclosure with you. Reporters are credited in the release notes unless you would rather
not be. There is no bug bounty.

## Scope

This server issues access tokens, stores Google credentials, and can spend money on a live
ad account. We treat as security issues, at minimum:

- any path that logs, echoes or returns an access token, refresh token, client secret or
  stored platform credential
- authentication bypass on `/mcp` — an unauthenticated `tools/call` of any kind
- an authorization code or refresh token that can be redeemed twice
- a token minted for one resource being accepted for another
- cross-account access: any way a token for one user reaches another user's data
- SSRF in the image-fetch path, or a way past `OutboundUrlPolicy`
- consent or an ad-account connection granted without authenticating as the operator
- any way to activate a campaign this server did not create, or to set a budget above the
  configured cap — those checks are the only thing standing between a model and real spend

## Out of scope

- Findings that require the attacker to already hold the token-signing key or an issued
  access token
- Missing hardening headers on `/mcp`, which is a server-to-server endpoint
- The deployment you run it in — reverse proxy, TLS, firewall, database access
- Denial of service through sheer request volume

## Running this safely

- Terminate TLS in front of it. The bearer token travels in a header.
- Keep `oauth.access-token-secret` distinct from every other signing key you use.
- Store `mcp.token-encryption-key` in a secret manager, not a config file. Losing it means
  every user must reconnect; leaking it means every stored credential is readable.
- Set `oauth.redirect-hosts-allowlist` if you do not need open registration.
- Give the server a database role that can reach its own tables and nothing else.
- Keep `mcp.default-max-daily-budget-cents` at a number you would not mind losing. It is
  enforced server-side precisely because a model cannot be trusted not to argue with it.
- Use a Google account with access to the one ad account you intend to manage, not your
  own everything-account.
