package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.AggregateResult;
import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.Change;
import com.ezclassify.rest.contract.CollectedNotice;
import com.ezclassify.rest.contract.ExpandableStringEnum;
import com.ezclassify.rest.contract.ExplanationTrace;
import com.ezclassify.rest.contract.Fix;
import com.ezclassify.rest.contract.FixConceptReference;
import com.ezclassify.rest.contract.TraceEntry;
import com.ezclassify.rest.contract.TraceInference;
import com.ezclassify.rest.contract.TraceRepair;
import com.ezclassify.rest.contract.TraceValidationIssue;
import com.ezclassify.rest.contract.ValidationError;
import com.ezclassify.rest.contract.Warning;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared Gson configuration for the typed wire records and their contract enums.
 *
 * <p>The contract is open-response and closed-request, and the server enforces the closed side.
 * Gson ignores response members this SDK does not know. Every contract enum is an {@link
 * ExpandableStringEnum}: a token this SDK does not know decodes to an instance that keeps it, and
 * encoding writes the token unchanged, so a value read from one response can go back in the next
 * request. A required boolean or number the response leaves out is refused rather than read as
 * {@code false} or zero.
 */
final class WireJson {
  // A JSON number inside an untyped value (assertion metadata, opaque rule outcomes) is read with
  // the same literal-preserving mapping as the tree readers use. Gson's default reads it as a
  // Double, so an integer 3 would go back to the server as 3.0.
  private static final Gson GSON =
      new GsonBuilder()
          .registerTypeAdapterFactory(new ContractEnumAdapterFactory())
          .registerTypeAdapterFactory(new RequiredPrimitiveAdapterFactory())
          .setObjectToNumberStrategy(in -> Json.toNumber(in.nextString()))
          .create();

  // Context projections retain their established empty-text/list conventions. The ordinary
  // decoder stays separate so this policy cannot fill fields in other response records.
  private static final Gson CONTEXT_GSON =
      GSON.newBuilder()
          .registerTypeAdapterFactory(new ImmutableListAdapterFactory())
          .registerTypeAdapterFactory(new ContextProjectionAdapterFactory())
          .create();

  private WireJson() {}

  static <T> T projectedFromTree(JsonElement value, Class<T> type) {
    try {
      return CONTEXT_GSON.fromJson(value, type);
    } catch (JsonParseException | IllegalStateException e) {
      throw new MalformedResponseException(
          "REST response cannot be decoded as " + type.getSimpleName());
    }
  }

  static <T> List<T> projectedListFromTree(JsonElement value, Class<T> elementType) {
    if (value == null || value.isJsonNull()) return List.of();
    try {
      return CONTEXT_GSON.fromJson(
          value, TypeToken.getParameterized(List.class, elementType).getType());
    } catch (JsonParseException | IllegalStateException e) {
      throw new MalformedResponseException(
          "REST response cannot be decoded as a list of " + elementType.getSimpleName());
    }
  }

  /** Context defaults are applied to named JSON members before Gson binds the generated record. */
  private static final class ContextProjectionAdapterFactory implements TypeAdapterFactory {
    private static final Set<Class<?>> PROJECTED =
        Set.of(
            Assertion.class,
            ValidationError.class,
            Warning.class,
            Fix.class,
            FixConceptReference.class,
            Change.class,
            ExplanationTrace.class,
            TraceEntry.class,
            TraceRepair.class,
            TraceInference.class,
            TraceValidationIssue.class,
            CollectedNotice.class,
            AggregateResult.class);

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      Class<? super T> raw = type.getRawType();
      if (!PROJECTED.contains(raw)) return null;
      // CollectedNotice is the Java-native name of the canonical Notice wire record.
      String schema = raw == CollectedNotice.class ? "Notice" : raw.getSimpleName();
      ResponseShape shape = ResponseShapes.COMPONENTS.get(schema);
      if (shape == null || shape.kind != ResponseShape.Kind.OBJECT) {
        throw new IllegalStateException("No generated object shape for " + schema);
      }
      TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
      TypeAdapter<JsonElement> trees = gson.getAdapter(JsonElement.class);
      RecordComponent[] components = raw.getRecordComponents();
      return new TypeAdapter<T>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          delegate.write(out, value);
        }

        @Override
        public T read(JsonReader in) throws IOException {
          JsonElement tree = trees.read(in);
          if (tree.isJsonObject()) {
            JsonObject object = tree.getAsJsonObject().deepCopy();
            for (RecordComponent component : components) {
              normalize(object, component, shape.required.contains(component.getName()));
            }
            return delegate.fromJsonTree(object);
          }
          return delegate.fromJsonTree(tree);
        }
      };
    }

    private static void normalize(JsonObject object, RecordComponent component, boolean required) {
      String name = component.getName();
      Class<?> type = component.getType();
      boolean absent = Json.isNull(object, name);
      if (type == String.class) {
        object.addProperty(name, absent && !required ? null : Json.optString(object, name));
      } else if (ExpandableStringEnum.class.isAssignableFrom(type)) {
        object.addProperty(
            name, required ? Json.requiredString(object, name) : Json.nullableString(object, name));
      } else if (type == boolean.class) {
        object.addProperty(name, Json.requiredBoolean(object, name));
      } else if (type == Boolean.class) {
        object.addProperty(name, absent ? null : Json.optBoolean(object, name));
      } else if (type == List.class) {
        if (absent && !required) {
          object.add(name, JsonNull.INSTANCE);
          return;
        }
        JsonArray array = Json.optArray(object, name);
        var item = ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
        // Fix reference lists have no absent-list projection; other result arrays default empty.
        if (array == null && item == FixConceptReference.class) {
          throw new MalformedResponseException("REST response is missing array field " + name);
        }
        if (array == null) array = new JsonArray();
        if (item == String.class) {
          JsonArray strings = new JsonArray();
          for (int i = 0; i < array.size(); i++) strings.add(Json.optString(array, i));
          array = strings;
        }
        object.add(name, array);
      } else if (type == Map.class || type.isRecord()) {
        JsonObject value = Json.optObject(object, name);
        if (value != null
            && type == Map.class
            && ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[1]
                == String.class) {
          JsonObject strings = new JsonObject();
          for (String key : value.keySet()) {
            strings.addProperty(key, Json.isNull(value, key) ? null : Json.optString(value, key));
          }
          value = strings;
        }
        object.add(name, value == null ? JsonNull.INSTANCE : value);
      } else {
        throw new IllegalStateException("Unsupported Context projection component " + component);
      }
    }
  }

  /** Lists exposed by Context projections are immutable at every record depth. */
  private static final class ImmutableListAdapterFactory implements TypeAdapterFactory {
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      if (type.getRawType() != List.class) return null;
      TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
      return new TypeAdapter<T>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          delegate.write(out, value);
        }

        @Override
        // The delegate has this exact List<T> type; copying changes no element type.
        @SuppressWarnings("unchecked")
        public T read(JsonReader in) throws IOException {
          T value = delegate.read(in);
          return value == null ? null : (T) List.copyOf((List<?>) value);
        }
      };
    }
  }

  /** Serializes a request body; a contract enum is written as its raw wire token. */
  static JsonElement toTree(Object value) {
    return GSON.toJsonTree(value);
  }

  /** Deserializes a typed response; a contract enum token this SDK does not know is kept as is. */
  static <T> T fromTree(JsonElement value, Class<T> type) {
    return GSON.fromJson(value, type);
  }

  /** Deserializes an array response into an immutable typed list. */
  static <T> List<T> listFromTree(JsonElement value, Class<T> elementType) {
    if (value == null || value.isJsonNull()) return List.of();
    return List.copyOf(
        GSON.fromJson(value, TypeToken.getParameterized(List.class, elementType).getType()));
  }

  /**
   * Refuses a contract record whose required boolean or number is missing.
   *
   * <p>The generator types a required boolean or number as a Java primitive and an optional one as
   * its wrapper. A primitive has no absent value, so Gson would fill a missing one with {@code
   * false} or zero: a result without {@code valid} would read as not valid, and one without {@code
   * inputWasValid} as wrong as written. The client never invents a value the server did not send,
   * so a record whose primitive member is missing or null is a malformed response.
   */
  private static final class RequiredPrimitiveAdapterFactory implements TypeAdapterFactory {
    private static final String CONTRACT_PACKAGE = ExpandableStringEnum.class.getPackageName();

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      Class<? super T> raw = type.getRawType();
      if (!raw.isRecord() || !CONTRACT_PACKAGE.equals(raw.getPackageName())) return null;
      // Gson binds a record component by its name, so that is the member to look for.
      List<String> required =
          Arrays.stream(raw.getRecordComponents())
              .filter(component -> component.getType().isPrimitive())
              .map(RecordComponent::getName)
              .toList();
      if (required.isEmpty()) return null;
      TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
      TypeAdapter<JsonElement> trees = gson.getAdapter(JsonElement.class);
      return new TypeAdapter<T>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          delegate.write(out, value);
        }

        @Override
        public T read(JsonReader in) throws IOException {
          JsonElement tree = trees.read(in);
          if (tree.isJsonObject()) {
            JsonObject object = tree.getAsJsonObject();
            for (String name : required) {
              JsonElement member = object.get(name);
              if (member == null || member.isJsonNull()) {
                throw new JsonParseException(
                    raw.getSimpleName() + " is missing its required member " + name);
              }
            }
          }
          return delegate.fromJsonTree(tree);
        }
      };
    }
  }

  /**
   * Serves every {@link ExpandableStringEnum} through its own codec: {@code toWire()} to encode and
   * the type's lenient {@code fromString(String)} to decode.
   *
   * <p>One factory rather than a registration per type, so a contract enum the generator adds is
   * covered the day it appears. A plain Java {@code enum} in the contract package is refused rather
   * than left to Gson's default adapter, which matches Java constant names instead of wire tokens
   * and turns anything else into {@code null}.
   */
  private static final class ContractEnumAdapterFactory implements TypeAdapterFactory {
    private static final String CONTRACT_PACKAGE = ExpandableStringEnum.class.getPackageName();

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      Class<? super T> raw = type.getRawType();
      if (raw.isEnum()
          && CONTRACT_PACKAGE.equals(raw.getPackageName())
          && !isValueEnumOfAContractEnum(raw)) {
        throw new IllegalStateException(
            raw.getName() + " is a Java enum; contract enums must extend ExpandableStringEnum");
      }
      if (!ExpandableStringEnum.class.isAssignableFrom(raw)) return null;
      MethodHandle decode;
      try {
        decode =
            MethodHandles.publicLookup()
                .findStatic(raw, "fromString", MethodType.methodType(raw, String.class));
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(
            raw.getName() + " is a contract enum without a public static fromString(String)", e);
      }
      return new TypeAdapter<T>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          if (value == null) {
            out.nullValue();
            return;
          }
          out.value(((ExpandableStringEnum<?>) value).toWire());
        }

        @Override
        @SuppressWarnings("unchecked")
        public T read(JsonReader in) throws IOException {
          JsonToken token = in.peek();
          if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
          }
          // JsonReader.nextString() would also accept a number and return its text; a contract
          // enum token is always a JSON string, so anything else is a malformed response.
          if (token != JsonToken.STRING) {
            throw new JsonParseException(
                "Expected a string token for " + raw.getSimpleName() + " but was " + token);
          }
          return (T) decode(decode, in.nextString());
        }
      };
    }

    /** Whether {@code type} is the nested {@code Value} enum a contract enum switches over. */
    private static boolean isValueEnumOfAContractEnum(Class<?> type) {
      Class<?> owner = type.getDeclaringClass();
      return owner != null && ExpandableStringEnum.class.isAssignableFrom(owner);
    }

    private static Object decode(MethodHandle handle, String token) {
      try {
        return handle.invoke(token);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
