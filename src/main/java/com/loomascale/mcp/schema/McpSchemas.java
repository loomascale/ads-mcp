package com.loomascale.mcp.schema;

import com.loomascale.mcp.money.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

// Tiny helpers for building JSON Schema (draft-07 object schemas) for tool inputs,
// so each tool stays declarative.
public final class McpSchemas {

  // Money crosses this boundary as a major-unit decimal plus a currency code.
  // Minor-unit integers used to travel alone, and the model read a budget of
  // 80000 cents on a UAH account as "80 000 UAH/день" instead of 800 UAH.
  public static final String CURRENCY_FIELD_DESCRIPTION =
      "ISO currency code that every money field in this result is denominated in, e.g. UAH or USD."
          + " Null when it could not be resolved — do not assume a currency then.";

  private static final String MONEY_UNITS =
      " Amount in the account currency (see the currency field), e.g. 800.00 with currency UAH"
          + " means 800 UAH — never a minor-unit integer.";

  private McpSchemas() {}

  // Nullable decimal money property with the unit contract spelled out.
  public static ObjectNode moneyProp(
      ObjectNode schema, String name, String description) {
    return nullableProp(schema, name, "number", description + MONEY_UNITS);
  }

  public static String moneyInputDescription(String description) {
    return description
        + " In the account's own currency (the currency field of google_list_campaigns or"
        + " meta_list_campaigns), not in dollars and not in minor units.";
  }

  public static ObjectNode object(ObjectMapper mapper) {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    schema.putObject("properties");
    return schema;
  }

  public static ObjectNode prop(ObjectNode schema, String name, String type, String description) {
    ObjectNode props = (ObjectNode) schema.get("properties");
    ObjectNode p = props.putObject(name);
    p.put("type", type);
    p.put("description", description);
    return p;
  }

  // Array-of-string property whose items are constrained to an enum.
  public static ObjectNode arrayProp(
      ObjectNode schema, String name, String description, List<String> itemValues) {
    ObjectNode props = (ObjectNode) schema.get("properties");
    ObjectNode p = props.putObject(name);
    p.put("type", "array");
    p.put("description", description);
    ObjectNode items = p.putObject("items");
    items.put("type", "string");
    enumValues(items, itemValues);
    return p;
  }

  // Array-of-objects property. Returns the item schema (an object schema with an
  // empty "properties") so the caller keeps adding fields to it with prop().
  public static ObjectNode objectArrayProp(ObjectNode schema, String name, String description) {
    ObjectNode props = (ObjectNode) schema.get("properties");
    ObjectNode p = props.putObject(name);
    p.put("type", "array");
    p.put("description", description);
    ObjectNode items = p.putObject("items");
    items.put("type", "object");
    items.putObject("properties");
    return items;
  }

  // Array-of-strings property with no enum constraint.
  public static ObjectNode stringArrayProp(ObjectNode schema, String name, String description) {
    ObjectNode props = (ObjectNode) schema.get("properties");
    ObjectNode p = props.putObject(name);
    p.put("type", "array");
    p.put("description", description);
    p.putObject("items").put("type", "string");
    return p;
  }

  // Open string-keyed map, e.g. Meta's action breakdowns whose keys are not known
  // ahead of time: { type: object, additionalProperties: { type: <valueType> } }.
  public static ObjectNode mapProp(
      ObjectNode schema, String name, String valueType, String description) {
    ObjectNode props = (ObjectNode) schema.get("properties");
    ObjectNode p = props.putObject(name);
    p.put("type", "object");
    p.put("description", description);
    p.putObject("additionalProperties").put("type", valueType);
    return p;
  }

  // Property whose DTO getter is nullable, so the value can arrive as JSON null.
  public static ObjectNode nullableProp(
      ObjectNode schema, String name, String type, String description) {
    ObjectNode props = (ObjectNode) schema.get("properties");
    ObjectNode p = props.putObject(name);
    ArrayNode types = p.putArray("type");
    types.add(type);
    types.add("null");
    p.put("description", description);
    return p;
  }

  public static void enumValues(ObjectNode prop, List<String> values) {
    ArrayNode arr = prop.putArray("enum");
    values.forEach(arr::add);
  }

  public static void required(ObjectNode schema, String... names) {
    ArrayNode req = schema.putArray("required");
    for (String n : names) {
      req.add(n);
    }
  }
}
