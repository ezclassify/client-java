package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.ExpandableStringEnum;
import com.ezclassify.rest.contract.ProcessingContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mechanical copies of generated records and their caller-owned collections. */
final class OptionSnapshots {
  private OptionSnapshots() {}

  static List<Assertion> assertions(List<Assertion> values) {
    return values == null ? null : values.stream().map(value -> (Assertion) copy(value)).toList();
  }

  static ProcessingContext context(ProcessingContext value) {
    return (ProcessingContext) copy(value);
  }

  // Record components are copied without a JSON round trip, which would change metadata number
  // types and their wire spelling. The generated record constructor remains the shape owner.
  private static Object copy(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (var entry : map.entrySet()) {
        String key = wireKey(entry.getKey());
        // Different Java keys can spell the same JSON property. Let the wire
        // serializer settle those collisions, including omitted null values.
        if (result.containsKey(key)) return wireSnapshot(map);
        result.put(key, copy(entry.getValue()));
      }
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> list) return list.stream().map(OptionSnapshots::copy).toList();
    if (value != null
        && value.getClass().isRecord()
        && value.getClass().getPackageName().equals("com.ezclassify.rest.contract")) {
      RecordComponent[] components = value.getClass().getRecordComponents();
      Class<?>[] types = new Class<?>[components.length];
      Object[] values = new Object[components.length];
      try {
        for (int i = 0; i < components.length; i++) {
          types[i] = components[i].getType();
          values[i] = copy(components[i].getAccessor().invoke(value));
        }
        return value.getClass().getDeclaredConstructor(types).newInstance(values);
      } catch (ReflectiveOperationException exception) {
        throw new IllegalArgumentException(
            "Cannot snapshot the contract record " + value.getClass().getName(), exception);
      }
    }
    if (value instanceof JsonElement json) return wireSnapshot(json);
    if (value != null && value.getClass().isArray()) {
      List<Object> result = new ArrayList<>();
      for (int i = 0; i < Array.getLength(value); i++) result.add(copy(Array.get(value, i)));
      return Collections.unmodifiableList(result);
    }
    if (value instanceof java.util.concurrent.atomic.AtomicInteger number) return number.intValue();
    if (value instanceof java.util.concurrent.atomic.AtomicLong number) return number.longValue();
    if (value == null
        || value instanceof String
        || immutableNumber(value)
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>
        || value instanceof ExpandableStringEnum<?>) return value;
    // Metadata may contain another Gson-serializable object. Freeze its wire value rather than
    // retain a caller-owned object; generated record fields have already been copied above.
    return wireSnapshot(value);
  }

  // JSON object keys are wire property names, not caller-owned objects. Ask the
  // existing serializer for their spelling rather than retain a mutable key.
  private static String wireKey(Object value) {
    if (value instanceof String text) return text;
    return WireJson.toTree(Collections.singletonMap(value, true))
        .getAsJsonObject()
        .keySet()
        .iterator()
        .next();
  }

  private static boolean immutableNumber(Object value) {
    if (value == null) return false;
    Class<?> type = value.getClass();
    return type == Byte.class
        || type == Short.class
        || type == Integer.class
        || type == Long.class
        || type == Float.class
        || type == Double.class
        || type == BigDecimal.class
        || type == BigInteger.class;
  }

  // Gson's tree copy can retain a caller-owned Number inside a JsonPrimitive. Parsing the
  // serialized wire value detaches every nested number without rounding its JSON spelling.
  private static JsonElement wireSnapshot(Object value) {
    return JsonParser.parseString(WireJson.toTree(value).toString());
  }
}
