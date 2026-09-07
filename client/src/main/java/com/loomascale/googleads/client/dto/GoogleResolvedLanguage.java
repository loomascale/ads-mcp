package com.loomascale.googleads.client.dto;

// A language entry a tool was given, resolved to something Google accepts. `requested`
// is what the caller wrote — an id, an ISO code or a name — and the rest is the language
// constant it matched, so result text can name the language back rather than echo an id.
public record GoogleResolvedLanguage(String requested, String id, String code, String name) {}
