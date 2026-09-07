package com.loomascale.mcp.spi;

// The per-platform vocabulary the shared auth gate needs: what to call the platform in a
// message, which granted scope a write requires, and what to say when the account owns no
// ad account.
//
// This exists so the gate itself — a security control — is written once. Duplicating it
// per platform would guarantee the two copies drift, and the parts that genuinely differ
// between Google and Meta are only words and one scope name, which is data.
public interface AdsPlatformDescriptor {

  String platformKey();

  // Name shown to the user, e.g. "Google Ads".
  String displayName();

  // The granted scope a write tool requires: the adwords scope for Google,
  // ads_management for Meta. Both are separately deselectable on the consent screen, so a
  // connection can be healthy and still unable to write.
  String writeScope();

  // What to tell a user whose grant is fine but who owns no ad account. Empty when the
  // platform has no such state, in which case the gate falls back to the expired message.
  default java.util.Optional<String> noAdAccountMessage() {
    return java.util.Optional.empty();
  }
}
