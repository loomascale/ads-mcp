package com.loomascale.googleads.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

// Turns a Google Ads request body into one log-safe line. Kept static and separate from
// GoogleAdsApiClient because the client's HTTP send path has no test seam, while every
// formatting decision here is worth pinning.
//
// Log-safe means two things: bounded, so a 500-operation mutate cannot flood the log, and
// free of both OAuth tokens and the click identifiers an offline conversion upload carries.
public final class GoogleAdsRequestLog {

  // Long enough for a full GAQL query or one mutate operation, short enough that a failing
  // batch upload stays readable.
  public static final int MAX_REQUEST_CHARS = 2000;

  // Google access tokens are ya29.…, refresh tokens 1//…; neither may reach a log or the model.
  private static final String TOKEN_REPLACEMENT = "[redacted]";
  private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("ya29\\.[0-9A-Za-z_\\-.]+");
  private static final Pattern REFRESH_TOKEN_PATTERN = Pattern.compile("1//[0-9A-Za-z_\\-]+");

  // Where Google expects the bytes of an image asset. Never logged verbatim.
  private static final String ASSET_BYTES_FIELD = "data";

  private GoogleAdsRequestLog() {}

  // The `request=` half of a failure line. A GAQL query and the operation Google rejected
  // are the two things that actually explain a 400, so those are printed in full; every
  // other body is reduced to its shape, because uploadClickConversions carries gclids and
  // order ids that have no business being in a log.
  public static String describe(JsonNode body, OptionalInt failingOperationIndex) {
    if (body == null || body.isMissingNode() || body.isNull()) {
      return "(no body)";
    }
    if (body.hasNonNull("query")) {
      return truncate(body.path("query").asText(), MAX_REQUEST_CHARS);
    }
    // A per-service mutate carries `operations`; the bulk googleAds:mutate carries
    // `mutateOperations`. Recognising only the first logged a bulk body as
    // "body fields=[mutateOperations[23]]", dropping the failing operation exactly when
    // it was the only thing worth having.
    JsonNode operations = body.path("operations");
    String label = "operations";
    if (!operations.isArray()) {
      operations = body.path("mutateOperations");
      label = "mutateOperations";
    }
    if (operations.isArray()) {
      String summary = operations.size() + " " + label;
      if (failingOperationIndex.isPresent()) {
        int index = failingOperationIndex.getAsInt();
        JsonNode failing = operations.path(index);
        if (!failing.isMissingNode()) {
          return summary
              + "; failing["
              + index
              + "]="
              + truncate(withoutAssetBytes(failing).toString(), MAX_REQUEST_CHARS);
        }
      }
      // No index to point at, so the whole batch goes in — the cap is what bounds it.
      return summary + "; " + truncate(withoutAssetBytes(operations).toString(), MAX_REQUEST_CHARS);
    }
    return "body fields=" + shape(body);
  }

  // An image asset travels as base64 in imageAsset.data, so printing the operation that
  // carried it fills the whole MAX_REQUEST_CHARS budget with base64 and explains nothing.
  // The byte count is the part that matters — a wrong-sized image is a real failure cause.
  // Works on a copy: the node being described is the live request body.
  private static JsonNode withoutAssetBytes(JsonNode node) {
    if (!containsAssetBytes(node)) {
      return node;
    }
    JsonNode copy = node.deepCopy();
    redactAssetBytes(copy);
    return copy;
  }

  private static boolean containsAssetBytes(JsonNode node) {
    if (node.isObject()) {
      if (node.hasNonNull(ASSET_BYTES_FIELD) && node.path(ASSET_BYTES_FIELD).isTextual()) {
        return true;
      }
      for (JsonNode child : node) {
        if (containsAssetBytes(child)) {
          return true;
        }
      }
      return false;
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        if (containsAssetBytes(child)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void redactAssetBytes(JsonNode node) {
    if (node instanceof ObjectNode object) {
      JsonNode data = object.path(ASSET_BYTES_FIELD);
      if (data.isTextual()) {
        object.put(ASSET_BYTES_FIELD, "<" + decodedByteCount(data.asText()) + " bytes>");
      }
      for (JsonNode child : object) {
        redactAssetBytes(child);
      }
    } else if (node instanceof ArrayNode array) {
      for (JsonNode child : array) {
        redactAssetBytes(child);
      }
    }
  }

  // Close enough to be useful in a log without decoding the string: base64 is 4 characters
  // per 3 bytes, minus whatever padding the tail carries.
  private static int decodedByteCount(String base64) {
    int padding = 0;
    for (int i = base64.length() - 1; i >= 0 && base64.charAt(i) == '=' && padding < 2; i--) {
      padding++;
    }
    return Math.max(0, base64.length() / 4 * 3 - padding);
  }

  // Field names with array sizes, e.g. [conversions[12], partialFailure] — enough to tell
  // an empty upload from a full one without printing a single value.
  private static String shape(JsonNode body) {
    List<String> fields = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> entries = body.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      fields.add(
          entry.getValue().isArray()
              ? entry.getKey() + "[" + entry.getValue().size() + "]"
              : entry.getKey());
    }
    return fields.toString();
  }

  public static String truncate(String value, int maxChars) {
    if (value == null) {
      return null;
    }
    return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
  }

  public static String scrub(String message) {
    if (message == null) {
      return null;
    }
    String cleaned = ACCESS_TOKEN_PATTERN.matcher(message).replaceAll(TOKEN_REPLACEMENT);
    return REFRESH_TOKEN_PATTERN.matcher(cleaned).replaceAll(TOKEN_REPLACEMENT);
  }
}
