package com.loomascale.mcp.security;

// Pixel dimensions of a fetched image, read from its header rather than by decoding the
// whole file. Used to enforce the aspect-ratio and minimum-size rules ad platforms apply
// to creative assets, before an upload that would fail several API calls later.
public record ImageDimensions(int width, int height) {

  @Override
  public String toString() {
    return width + "x" + height;
  }
}
