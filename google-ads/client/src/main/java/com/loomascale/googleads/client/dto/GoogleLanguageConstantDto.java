package com.loomascale.googleads.client.dto;

// A targetable language from Google's language_constant table — some fifty rows that
// barely change. One query fetches the lot, which is enough both to name the criteria a
// campaign carries and to resolve what a caller wrote ("1000", "en" or "English").
public record GoogleLanguageConstantDto(String id, String code, String name) {}
