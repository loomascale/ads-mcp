package com.loomascale.googleads.client.dto;

// Google's requirements for one Performance Max image slot: how many assets it takes, the
// aspect ratio it demands and the smallest size it accepts.
//
// The asymmetry in how these are enforced is deliberate. Minimum dimensions are strict,
// because DIMENSIONS_NOT_ALLOWED is deterministic and worth pre-empting exactly. Maxima are
// permissive where Google's own surfaces disagree with each other, because a too-strict max
// refuses a legal edit while a too-loose one costs one clear error message. And the ratio
// carries a tolerance, because ASPECT_RATIO_NOT_ALLOWED has undocumented crop leeway and a
// tight local check would refuse images Google would have accepted.
public record GoogleMediaSlot(
    String fieldType,
    String argument,
    int min,
    int max,
    double aspectRatio,
    String aspectRatioLabel,
    int minWidth,
    int minHeight) {}
