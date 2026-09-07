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

## Build

```bash
./mvnw verify           # compile, test, and check formatting
./mvnw spotless:apply   # format
```

Formatting is google-java-format via spotless. CI runs `spotless:check` rather than
`apply`, so a build cannot silently rewrite the tree — run `apply` before pushing.

## Adding a tool

`McpToolRegistry` collects every `AdsTool` bean automatically. Two things do not follow
automatically, and both matter to a client rather than to you:

**`outputSchema()` must describe what `execute()` actually puts into `structured`.**
Declaring a field is a promise: every non-error path has to write it, so only fields
written unconditionally belong in `required`. A field written inside an `if` stays
optional.

**`annotations()` must be honest.** Clients use them to decide whether to ask the user
before acting. `readOnlyHint=true` means the tool performs no writes at all.
`destructiveHint=true` for anything that affects spend. A wrong value here spends
somebody's money without a prompt.

## Testing

The test that matters most is `McpCoreEndToEndTest`: it boots an application declaring
only a tool and drives the whole documented flow over HTTP. Unit tests of each service
pass happily while the wiring is broken, which is the failure that counts for a library
whose entire promise is "add the dependency". If you change wiring, that test is the
evidence.

Two harness details worth knowing before you fight them:

- Redirect following is **off**. The redirects are the thing under test, and a following
  client chases the configured issuer host instead of the random test port.
- The `Origin` check is verified with the JDK HTTP client, because `HttpURLConnection`
  silently drops an `Origin` header it did not set — the test would otherwise pass for the
  wrong reason.

## Pull requests

Green `build` and `secret-scan` are required. New behaviour needs a test. Conventional
commit subjects (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`).

Comments should explain *why*, not restate the code. The existing code is dense with the
reasoning behind non-obvious choices; please keep that up rather than stripping it.
