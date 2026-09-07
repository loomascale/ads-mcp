package com.loomascale.googleads.client.dto;

import java.util.List;

// A brand Google recognizes, from customers:suggestBrands. The only way to turn a name like
// "Nike" into the Knowledge Graph entity id a brand criterion needs.
public record GoogleBrandSuggestionDto(
    String entityId, String name, List<String> urls, String state) {}
