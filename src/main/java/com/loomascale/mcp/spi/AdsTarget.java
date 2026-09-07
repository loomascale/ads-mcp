package com.loomascale.mcp.spi;

// One ad account reachable through a connection. `loginHint` is the platform's
// on-behalf-of identifier — Google's login-customer-id (the manager account the target was
// discovered under), or a Meta Page access token. It arrives already decrypted: at-rest
// encryption is the store's business, not the caller's.
public record AdsTarget(String id, String name, String currency, String loginHint) {}
