package com.loomascale.mcp.security;

// An image that passed every check: the bytes, the content type sniffed from those
// bytes (not the one the server claimed), and a filename with a matching extension.
public record FetchedCreativeImage(byte[] bytes, String contentType, String fileName) {}
