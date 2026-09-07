## What this changes

<!-- and why. The reasoning is the part that is hard to recover later. -->

## Checklist

- [ ] `./mvnw verify` passes
- [ ] `./mvnw spotless:apply` run
- [ ] New behaviour has a test; changed wiring is covered by `McpCoreEndToEndTest`
- [ ] No new `${VAR:non-empty-default}` in any config file
- [ ] For a new or changed tool: `outputSchema()` matches what `execute()` writes, and
      `annotations()` are honest about whether it writes
- [ ] README updated if configuration or an extension point changed
