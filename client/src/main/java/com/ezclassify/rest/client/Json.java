package com.ezclassify.rest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gson access helpers with the exact wire-reading semantics the client relies on: {@code opt*}
 * readers treat a missing key and a JSON {@code null} identically and return the caller's default
 * ({@code ""} for strings, {@code 0} for ints, {@code false} for booleans), and the {@code put}
 * writers silently omit a key when the value is {@code null}, so request bodies never carry
 * explicit JSON nulls.
 *
 * <p>{@link #toJava(JsonElement)} converts a parsed tree to plain Java types (string-keyed maps,
 * lists, boxed primitives). JSON numbers map to {@link Integer}, {@link Long}, or {@link
 * BigInteger} for integral literals and to {@link BigDecimal} for decimal notation, preserving the
 * source literal: the conformance recording-key hashes are computed over these values, so the
 * mapping is part of the pinned test contract.
 */
final class Json {

  private Json() {}

  // --- parsing -------------------------------------------------------------

  /**
   * Parse a JSON object document.
   *
   * @throws MalformedResponseException if the text is not valid JSON or is not an object
   */
  static JsonObject parseObject(String s) {
    JsonElement parsed = parse(s);
    if (!parsed.isJsonObject()) {
      throw new MalformedResponseException("REST response is not the required JSON object");
    }
    return parsed.getAsJsonObject();
  }

  /**
   * Parse a JSON array document.
   *
   * @throws MalformedResponseException if the text is not valid JSON or is not an array
   */
  static JsonArray parseArray(String s) {
    JsonElement parsed = parse(s);
    if (!parsed.isJsonArray()) {
      throw new MalformedResponseException("REST response is not the required JSON array");
    }
    return parsed.getAsJsonArray();
  }

  /**
   * Parse any JSON document.
   *
   * @throws MalformedResponseException if the text is not valid JSON
   */
  static JsonElement parse(String s) {
    try {
      return JsonParser.parseString(s);
    } catch (RuntimeException e) {
      // The body itself is not echoed: it may carry request data or internal diagnostics.
      throw new MalformedResponseException("REST response was not valid JSON", e);
    }
  }

  // --- required readers (spec-required fields; absence is a protocol violation) ---

  /**
   * Boolean value of a field the contract marks required.
   *
   * @throws MalformedResponseException if the field is absent, null, or not a boolean
   */
  static boolean requiredBoolean(JsonObject o, String key) {
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
      throw new MalformedResponseException(
          "REST response is missing required boolean field " + key);
    }
    return e.getAsBoolean();
  }

  /**
   * Object under a field the contract marks required.
   *
   * @throws MalformedResponseException if the field is absent, null, or not an object
   */
  static JsonObject requiredObject(JsonObject o, String key) {
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonObject()) {
      throw new MalformedResponseException("REST response is missing required object field " + key);
    }
    return e.getAsJsonObject();
  }

  /**
   * String value of a field the contract marks required.
   *
   * @throws MalformedResponseException if the field is absent, null, or not a string
   */
  static String requiredString(JsonObject o, String key) {
    String value = nullableString(o, key);
    if (value == null) {
      throw new MalformedResponseException("REST response is missing required string field " + key);
    }
    return value;
  }

  /**
   * String value of a field that may be absent: {@code null} when absent or JSON {@code null}.
   * Unlike {@link #optString(JsonObject, String)} it does not stringify another JSON type.
   *
   * @throws MalformedResponseException if the field is present and not a string
   */
  static String nullableString(JsonObject o, String key) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull()) return null;
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
      throw new MalformedResponseException("REST response field " + key + " is not a string");
    }
    return e.getAsString();
  }

  // --- readers -------------------------------------------------------------

  /** True when the key is absent or holds a JSON null. */
  static boolean isNull(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e == null || e.isJsonNull();
  }

  /** String value of the key, or {@code ""} when absent or null. */
  static String optString(JsonObject o, String key) {
    return optString(o, key, "");
  }

  /** String value of the key, or {@code def} when absent or null. Non-strings stringify. */
  static String optString(JsonObject o, String key, String def) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull()) return def;
    return e.isJsonPrimitive() ? e.getAsString() : e.toString();
  }

  /** String value at the index, or {@code ""} when null. Non-strings stringify. */
  static String optString(JsonArray arr, int i) {
    JsonElement e = arr.get(i);
    if (e.isJsonNull()) return "";
    return e.isJsonPrimitive() ? e.getAsString() : e.toString();
  }

  /** Boolean value of the key, or {@code false} when absent, null, or non-coercible. */
  static boolean optBoolean(JsonObject o, String key) {
    return optBoolean(o, key, false);
  }

  /** Boolean value of the key, or {@code def} when absent, null, or non-coercible. */
  static boolean optBoolean(JsonObject o, String key, boolean def) {
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive()) return def;
    JsonPrimitive p = e.getAsJsonPrimitive();
    if (p.isBoolean()) return p.getAsBoolean();
    if (p.isString()) {
      if ("true".equalsIgnoreCase(p.getAsString())) return true;
      if ("false".equalsIgnoreCase(p.getAsString())) return false;
    }
    return def;
  }

  /** Int value of the key, or {@code 0} when absent, null, or non-numeric. */
  static int optInt(JsonObject o, String key) {
    return optInt(o, key, 0);
  }

  /** Int value of the key, or {@code def} when absent, null, or non-numeric. */
  static int optInt(JsonObject o, String key, int def) {
    Number n = optNumber(o.get(key));
    return n == null ? def : n.intValue();
  }

  /** Object under the key, or {@code null} when absent, null, or not an object. */
  static JsonObject optObject(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
  }

  /** Array under the key, or {@code null} when absent, null, or not an array. */
  static JsonArray optArray(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
  }

  private static Number optNumber(JsonElement e) {
    if (e == null || !e.isJsonPrimitive()) return null;
    JsonPrimitive p = e.getAsJsonPrimitive();
    if (p.isNumber()) return p.getAsNumber();
    if (p.isString()) {
      try {
        return Double.parseDouble(p.getAsString());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  // --- writers (omit-on-null, so bodies never carry explicit JSON nulls) ----

  static void put(JsonObject o, String key, String value) {
    if (value != null) o.addProperty(key, value);
  }

  static void put(JsonObject o, String key, JsonElement value) {
    if (value != null && !value.isJsonNull()) o.add(key, value);
  }

  /** JSON array of the given strings, in order. */
  static JsonArray stringArray(Iterable<String> values) {
    JsonArray arr = new JsonArray();
    for (String v : values) arr.add(v);
    return arr;
  }

  /** JSON array of the given strings, in order. */
  static JsonArray stringArray(String... values) {
    JsonArray arr = new JsonArray();
    for (String v : values) arr.add(v);
    return arr;
  }

  // --- plain-Java conversion -------------------------------------------------

  /** Deep-convert an object to an insertion-ordered string-keyed map; JSON nulls become null. */
  static Map<String, Object> toMap(JsonObject o) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> e : o.entrySet()) out.put(e.getKey(), toJava(e.getValue()));
    return out;
  }

  /** Deep-convert an array to a list; JSON nulls become null. */
  static List<Object> toList(JsonArray arr) {
    List<Object> out = new ArrayList<>(arr.size());
    for (JsonElement e : arr) out.add(toJava(e));
    return out;
  }

  /** Deep-convert any element to plain Java types. */
  static Object toJava(JsonElement e) {
    if (e == null || e.isJsonNull()) return null;
    if (e.isJsonObject()) return toMap(e.getAsJsonObject());
    if (e.isJsonArray()) return toList(e.getAsJsonArray());
    JsonPrimitive p = e.getAsJsonPrimitive();
    if (p.isBoolean()) return p.getAsBoolean();
    if (p.isString()) return p.getAsString();
    return toNumber(p.getAsNumber().toString());
  }

  /**
   * Map a JSON number literal to the narrowest Java type: {@link Integer}, {@link Long}, then
   * {@link BigInteger} for integral literals; {@link BigDecimal} for decimal or exponent notation,
   * preserving the source representation ({@code "1.50"} stays {@code 1.50}). A minus-signed
   * decimal or exponent literal whose value is zero ({@code "-0.0"}, {@code "-0e0"}) maps to {@code
   * -0.0d}, and so does the bare literal {@code "-0"}, which this helper classifies as decimal
   * notation even though it carries no decimal point. Every other integral literal, including
   * {@code "0"}, takes the integer path. Conformance recording and replayer keys are SHA-256 hashes
   * over the stable JSON form of request values, so a decoded number must re-serialize to the same
   * string the shared recording corpus hashed; a different type or rendering for {@code -0} would
   * miss its recording. Do not "simplify" this to {@code Double.parseDouble}.
   */
  static Number toNumber(String literal) {
    if (literal.indexOf('.') >= 0
        || literal.indexOf('e') >= 0
        || literal.indexOf('E') >= 0
        || "-0".equals(literal)) {
      BigDecimal bd = new BigDecimal(literal);
      if (literal.charAt(0) == '-' && BigDecimal.ZERO.compareTo(bd) == 0) return -0.0d;
      return bd;
    }
    try {
      return Integer.valueOf(literal);
    } catch (NumberFormatException ignored) {
      // fall through to wider types
    }
    try {
      return Long.valueOf(literal);
    } catch (NumberFormatException ignored) {
      // fall through to BigInteger
    }
    return new BigInteger(literal);
  }
}
