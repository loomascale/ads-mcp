#!/usr/bin/env bash
# Fails if any committed application config uses ${VAR:default} with a non-empty
# default.
#
# This is the single check that would have prevented the credential leak this
# project was extracted away from. A non-secret default belongs in the Java
# @ConfigurationProperties field, never duplicated into YAML — that duplication is
# exactly where ~18 live credentials came to be committed, because a placeholder
# default looks like documentation and behaves like a hardcoded value.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
PATTERN='\$\{[A-Za-z0-9_.-]+:[^}[:space:]][^}]*\}'

if [[ "${1:-}" == "--staged" ]]; then
  FILES=$(git diff --cached --name-only --diff-filter=ACMR \
          | grep -E 'application.*\.(ya?ml|properties)$' || true)
else
  FILES=$(git ls-files | grep -E 'application.*\.(ya?ml|properties)$' || true)
fi
[[ -z "$FILES" ]] && exit 0

FOUND=0
for f in $FILES; do
  [[ -f "$ROOT/$f" ]] || continue
  if grep -nE "$PATTERN" "$ROOT/$f" >/dev/null 2>&1; then
    echo "ERROR: $f uses \${VAR:default} with a non-empty default:" >&2
    # The offending value is redacted before printing, so the gate does not
    # itself leak what it just caught.
    grep -nE "$PATTERN" "$ROOT/$f" \
      | sed -E 's/(\$\{[A-Za-z0-9_.-]+:)[^}]*\}/\1<REDACTED>}/g' >&2
    FOUND=1
  fi
done

if [[ $FOUND -eq 1 ]]; then
  cat >&2 <<'MSG'

THE RULE: never write ${ENV_VAR:real-value}.

Write ${ENV_VAR:} — an empty default — and let the startup check fail loudly
naming the variable. Better still, do not mention the key in YAML at all: bind
it in a @ConfigurationProperties class with @NotBlank, so "required" is a fact
the compiler and the IDE can both see.

A "harmless" default today is a committed credential the first time someone
pastes a working value in to unblock themselves.
MSG
  exit 1
fi
