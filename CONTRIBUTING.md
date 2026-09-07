# Contributing

## Before anything else: secrets

This repository was extracted from a private codebase that had leaked roughly eighteen
live credentials — not through carelessness with a `.env`, but through a pattern that
looks completely reasonable:

```yaml
app-secret: ${META_APP_SECRET:9f3c...}
```

The environment-variable indirection reads as safe. The literal default **is** the
committed secret. Two rules and two gates exist so that cannot recur here.

**1. Never write `${ENV_VAR:real-value}`.** Write `${ENV_VAR:}`, or better, do not mention
the key in YAML at all and bind it in a `@ConfigurationProperties` class — then "required"
is a fact the compiler and your IDE can both see. `scripts/check-no-inline-defaults.sh`
and the `spring-placeholder-with-default` rule in `.gitleaks.toml` both fail the build if
you try.

**2. Install the hook before your first commit:**

```bash
brew install gitleaks          # or https://github.com/gitleaks/gitleaks/releases
git config core.hooksPath .githooks
```

Never `git commit --no-verify` to get past a finding. GitHub push protection will reject
the push anyway, and by then the secret is in your local history. If it is genuinely a
false positive, add a narrowly scoped rule to `.gitleaks.toml` with a comment saying why.
Do not add a path exemption — path exemptions are how an allowlist rots.

**Never add this repository as a git remote of a private one, or the reverse.** A single
`git fetch` puts that repository's commits into this object database, where they survive
in `refs/remotes` even if never merged.

## Layout

One repository, one version, so a change spanning the core and a platform is one commit
and one build:

```
mcp-core/            protocol, OAuth 2.1 server, shared ads abstractions
google-ads/client/   Google Ads API client — no Spring, no MCP
google-ads/server/   the runnable MCP server and its 42 tools
```

`google-ads/server` depends on `mcp-core` at `${project.version}`, so it resolves inside
the reactor. A fresh clone builds with nothing published anywhere.

## Build

```bash
./mvnw verify           # every module: compile, test, check formatting
./mvnw spotless:apply   # format

# just one module and what it needs
./mvnw -pl google-ads/server -am verify
```

Formatting is google-java-format via spotless. CI runs `spotless:check` rather than
`apply`, so a build cannot silently rewrite the tree — run `apply` before pushing.

## Adding a tool

`McpToolRegistry` collects every `AdsTool` bean automatically. Three things do not follow
automatically, and all of them matter to a client rather than to you:

**`outputSchema()` must describe what `execute()` actually puts into `structured`.**
Declaring a field is a promise: every non-error path has to write it, so only fields
written unconditionally belong in `required`. A field written inside an `if` stays
optional.

**`annotations()` must be honest.** Clients use them to decide whether to ask the user
before acting. `readOnlyHint=true` means the tool performs no writes at all.
`destructiveHint=true` for anything that affects spend. A wrong value here spends
somebody's money without a prompt.

**A write tool must record a `WriteKind`.** `audit.record(userId, name(), WriteKind.CREATE,
…)` — and `CREATE` specifically is what makes an object activatable later. Get it wrong and
either a campaign can never be activated, or one this server did not create can be.

## Testing

The test that matters most is `GoogleAdsMcpBootTest`: it boots the real application
against H2 and asserts the tool **count**, not merely that the context loads. The failure
it guards against is silent — if component scanning does not reach the tools, the registry
reports zero, every unit test still passes, the server starts cleanly, and the model is
simply told this server can do nothing.

It also pins tool-name uniqueness, `outputSchema` validity, and that no read-only tool
claims to be destructive or asks for write scope.

Testing against Google itself is not part of the suite: it needs an approved developer
token and a real account, and a test that spends money is not a test. Verify changes to
the ads client against a Google Ads *test account* by hand, and say in the PR that you
did.

## Pull requests

Green `build` and `secret-scan` are required. New behaviour needs a test. Conventional
commit subjects (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`).

Comments should explain *why*, not restate the code. The existing code is dense with the
reasoning behind non-obvious choices; please keep that up rather than stripping it.
