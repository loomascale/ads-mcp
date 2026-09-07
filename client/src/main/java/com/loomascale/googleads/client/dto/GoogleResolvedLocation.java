package com.loomascale.googleads.client.dto;

// A location entry a tool was given, resolved to something Google accepts.
// `requested` is what the caller wrote (an id or a place name), `geoTargetId` the geo
// target constant it maps to, and `name` its canonical name, which is what result text
// reports back so nobody has to trust that "Kyiv" meant the city and not the oblast.
public record GoogleResolvedLocation(String requested, String geoTargetId, String name) {}
