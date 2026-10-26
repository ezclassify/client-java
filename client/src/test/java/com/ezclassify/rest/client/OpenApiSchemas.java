package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * The request and success-response schemas of the vendored OpenAPI document, and a validator for
 * the JSON Schema subset they use.
 *
 * <p>The validator fails closed: a schema keyword it does not implement throws rather than being
 * skipped, so a contract that starts using one forces this harness to learn it instead of silently
 * passing everything that keyword would have rejected.
 *
 * <p>Requests are checked as the contract writes them, and the request schemas already close their
 * objects. Responses are open in the contract, so a server can add members without breaking a
 * pinned client; a test's canned response is checked with every object that declares its members
 * closed instead, so a stub cannot carry a member the contract does not name, or miss one it
 * requires, and still pass.
 */
final class OpenApiSchemas {

  record Operation(String method, String path) {}

  private static final Set<String> ANNOTATIONS =
      Set.of(
          "description",
          "title",
          "default",
          "example",
          "examples",
          "deprecated",
          "readOnly",
          "writeOnly",
          "$comment");

  private static final String REF_PREFIX = "#/components/schemas/";

  private final JsonObject components;
  private final Map<Operation, JsonObject> requestSchemas;
  private final Map<Operation, JsonObject> responseSchemas;
  private final Map<Operation, Set<String>> queryParameters;
  private final Map<String, Operation> operationIds;

  private OpenApiSchemas(
      JsonObject components,
      Map<Operation, JsonObject> requestSchemas,
      Map<Operation, JsonObject> responseSchemas,
      Map<Operation, Set<String>> queryParameters,
      Map<String, Operation> operationIds) {
    this.components = components;
    this.requestSchemas = requestSchemas;
    this.responseSchemas = responseSchemas;
    this.queryParameters = queryParameters;
    this.operationIds = operationIds;
  }

  /** The component schema names the contract declares. */
  Set<String> componentNames() {
    return components.keySet();
  }

  /**
   * The property names a component declares: its own, or for a union of object alternatives every
   * alternative's.
   */
  Set<String> componentProperties(String name) {
    JsonObject schema = resolve(REF_PREFIX + name);
    Set<String> names = new TreeSet<>();
    if (schema.has("properties")) names.addAll(schema.getAsJsonObject("properties").keySet());
    for (String union : List.of("anyOf", "oneOf")) {
      if (!schema.has(union)) continue;
      for (JsonElement branch : schema.getAsJsonArray(union)) {
        JsonObject alternative = branch.getAsJsonObject();
        if (alternative.has("$ref")) alternative = resolve(alternative.get("$ref").getAsString());
        if (alternative.has("properties")) {
          names.addAll(alternative.getAsJsonObject("properties").keySet());
        }
      }
    }
    return names;
  }

  /** Every operation the contract declares, by its {@code operationId}. */
  Map<String, Operation> operations() {
    return operationIds;
  }

  /**
   * The query parameters an operation declares. The server refuses any other with a 400, so a
   * client that sends one fails every call that carries it.
   */
  Set<String> declaredQueryParameters(String method, String path) {
    Set<String> names = queryParameters.get(new Operation(method, path));
    assertNotNull(names, "The contract declares no operation " + method + " " + path);
    return names;
  }

  Set<Operation> operationsWithBodies() {
    return requestSchemas.keySet();
  }

  /** The operations on {@code path} that declare a JSON success response. */
  List<Operation> operationsWithResponses(String path) {
    return responseSchemas.keySet().stream().filter(op -> op.path().equals(path)).toList();
  }

  static OpenApiSchemas load() {
    Path spec =
        Path.of(System.getProperty("user.dir")).resolve("..").resolve("vendor/spec/openapi.yaml");
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(Integer.MAX_VALUE);
    Yaml yaml =
        new Yaml(
            new SafeConstructor(options),
            new Representer(new DumperOptions()),
            new DumperOptions(),
            options,
            new CoreSchemaResolver());
    JsonObject root;
    try (Reader reader = Files.newBufferedReader(spec, StandardCharsets.UTF_8)) {
      root = toJson(yaml.load(reader)).getAsJsonObject();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + spec, e);
    }

    JsonObject components = root.getAsJsonObject("components").getAsJsonObject("schemas");
    Map<Operation, JsonObject> requests = new LinkedHashMap<>();
    Map<Operation, JsonObject> responses = new LinkedHashMap<>();
    Map<Operation, Set<String>> queries = new LinkedHashMap<>();
    Map<String, Operation> ids = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> path : root.getAsJsonObject("paths").entrySet()) {
      for (Map.Entry<String, JsonElement> op : path.getValue().getAsJsonObject().entrySet()) {
        Operation operation = new Operation(op.getKey(), path.getKey());
        JsonElement operationId = walk(op.getValue(), "operationId");
        assertNotNull(operationId, op.getKey() + " " + path.getKey() + " declares no operationId");
        assertNull(
            ids.put(operationId.getAsString(), operation), "duplicate operationId " + operationId);
        Set<String> query = new TreeSet<>();
        JsonElement parameters = walk(op.getValue(), "parameters");
        if (parameters != null && parameters.isJsonArray()) {
          for (JsonElement parameter : parameters.getAsJsonArray()) {
            JsonObject declared = parameter.getAsJsonObject();
            if ("query".equals(declared.get("in").getAsString())) {
              query.add(declared.get("name").getAsString());
            }
          }
        }
        queries.put(operation, query);
        JsonElement request =
            walk(op.getValue(), "requestBody", "content", "application/json", "schema");
        if (request != null && request.isJsonObject()) {
          requests.put(operation, request.getAsJsonObject());
        }
        JsonElement response =
            walk(op.getValue(), "responses", "200", "content", "application/json", "schema");
        if (response != null && response.isJsonObject()) {
          responses.put(operation, response.getAsJsonObject());
        }
      }
    }
    assertFalse(requests.isEmpty(), "Read no request schemas out of vendor/spec/openapi.yaml");
    assertFalse(responses.isEmpty(), "Read no response schemas out of vendor/spec/openapi.yaml");
    return new OpenApiSchemas(components, requests, responses, queries, ids);
  }

  /** Validate a request body; returns one message per violation, empty when valid. */
  List<String> validateRequest(String method, String path, JsonElement body) {
    JsonObject schema = requestSchemas.get(new Operation(method, path));
    assertNotNull(schema, "The contract declares no request body for " + method + " " + path);
    List<String> errors = new ArrayList<>();
    check(body, schema, "$", false, errors);
    return errors;
  }

  /**
   * Validate a canned success response with every object that declares its members closed; returns
   * one message per violation, empty when valid.
   */
  List<String> validateResponse(String method, String path, JsonElement body) {
    JsonObject schema = responseSchemas.get(new Operation(method, path));
    assertNotNull(schema, "The contract declares no JSON response for " + method + " " + path);
    List<String> errors = new ArrayList<>();
    check(body, schema, "$", true, errors);
    return errors;
  }

  /**
   * Whether the contract accepts a success body, with responses open as the contract writes them.
   */
  boolean acceptsResponse(String method, String path, JsonElement body) {
    JsonObject schema = responseSchemas.get(new Operation(method, path));
    assertNotNull(schema, "The contract declares no JSON response for " + method + " " + path);
    return passes(body, schema, "$", false);
  }

  /**
   * A member the contract requires that a body carries: its JSONPath, the location of the object
   * that holds it (member names and array indexes from the root), and whether the contract lets it
   * be null.
   */
  record RequiredMember(String path, List<Object> parent, String name, boolean nullable) {}

  /**
   * Every member of a success body that the contract requires, at any depth. A union contributes
   * the members of each alternative the value satisfies.
   */
  List<RequiredMember> requiredMembers(String method, String path, JsonElement body) {
    JsonObject schema = responseSchemas.get(new Operation(method, path));
    assertNotNull(schema, "The contract declares no JSON response for " + method + " " + path);
    Map<String, RequiredMember> found = new LinkedHashMap<>();
    collectRequired(body, schema, "$", List.of(), found);
    return List.copyOf(found.values());
  }

  private void collectRequired(
      JsonElement value,
      JsonElement schemaNode,
      String at,
      List<Object> location,
      Map<String, RequiredMember> found) {
    if (!schemaNode.isJsonObject() || value == null || value.isJsonNull()) return;
    JsonObject schema = schemaNode.getAsJsonObject();
    if (schema.has("$ref")) {
      collectRequired(value, resolve(schema.get("$ref").getAsString()), at, location, found);
    }
    for (String union : List.of("anyOf", "oneOf")) {
      if (!schema.has(union)) continue;
      for (JsonElement branch : schema.getAsJsonArray(union)) {
        if (passes(value, branch, at, false)) collectRequired(value, branch, at, location, found);
      }
    }
    if (value.isJsonArray() && schema.has("items")) {
      JsonArray array = value.getAsJsonArray();
      for (int i = 0; i < array.size(); i++) {
        List<Object> next = new ArrayList<>(location);
        next.add(i);
        collectRequired(array.get(i), schema.get("items"), at + "[" + i + "]", next, found);
      }
    }
    if (!value.isJsonObject()) return;
    JsonObject object = value.getAsJsonObject();
    JsonObject properties =
        schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
    if (schema.has("required")) {
      for (JsonElement name : schema.getAsJsonArray("required")) {
        String member = name.getAsString();
        if (!object.has(member)) continue;
        String memberPath = at + segment(member);
        boolean nullable = properties.has(member) && admitsNull(properties.get(member));
        found.putIfAbsent(memberPath, new RequiredMember(memberPath, location, member, nullable));
      }
    }
    for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
      JsonElement member = object.get(property.getKey());
      if (member == null) continue;
      List<Object> next = new ArrayList<>(location);
      next.add(property.getKey());
      collectRequired(member, property.getValue(), at + segment(property.getKey()), next, found);
    }
    JsonElement additional = schema.get("additionalProperties");
    if (additional != null && additional.isJsonObject()) {
      for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
        if (properties.has(entry.getKey())) continue;
        List<Object> next = new ArrayList<>(location);
        next.add(entry.getKey());
        collectRequired(entry.getValue(), additional, at + segment(entry.getKey()), next, found);
      }
    }
  }

  /** Whether a schema admits JSON null as a value of its own. */
  private boolean admitsNull(JsonElement schemaNode) {
    if (!schemaNode.isJsonObject()) return false;
    JsonObject schema = schemaNode.getAsJsonObject();
    if (schema.has("$ref")) return admitsNull(resolve(schema.get("$ref").getAsString()));
    JsonElement type = schema.get("type");
    if (type != null) {
      if (type.isJsonArray()) {
        for (JsonElement t : type.getAsJsonArray()) if ("null".equals(t.getAsString())) return true;
      } else if ("null".equals(type.getAsString())) {
        return true;
      }
    }
    for (String union : List.of("anyOf", "oneOf")) {
      if (!schema.has(union)) continue;
      for (JsonElement branch : schema.getAsJsonArray(union)) if (admitsNull(branch)) return true;
    }
    return false;
  }

  /** A JSONPath member segment: dotted for an identifier, bracketed otherwise. */
  private static String segment(String name) {
    for (char c : name.toCharArray()) {
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        return "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
      }
    }
    return "." + name;
  }

  /**
   * Validate a body against a named component schema, such as {@code RateLimitError}, with every
   * object that declares its members closed; returns one message per violation, empty when valid.
   */
  List<String> validateComponent(String name, JsonElement body) {
    List<String> errors = new ArrayList<>();
    check(body, resolve(REF_PREFIX + name), "$", true, errors);
    return errors;
  }

  /**
   * Validate a canned success response for one Context operation, named as its route under the
   * Context prefix, such as {@code scheme/process}. The route must declare exactly one operation
   * with a JSON success response.
   */
  List<String> validateContextResponse(String route, JsonElement body) {
    String path = "/v1/ctx/{ctxId}/{version}/" + route;
    List<Operation> operations = operationsWithResponses(path);
    if (operations.size() != 1) {
      throw new IllegalStateException(
          "Expected one operation with a JSON response at " + path + ", found " + operations);
    }
    return validateResponse(operations.get(0).method(), path, body);
  }

  /**
   * Top-level property names an operation's request schema declares. Follows {@code $ref} and the
   * {@code anyOf} and {@code if}/{@code then}/{@code else} branches, so a field declared on any
   * branch counts.
   */
  Set<String> declaredProperties(String method, String path) {
    JsonObject root = requestSchemas.get(new Operation(method, path));
    assertNotNull(root, "The contract declares no request body for " + method + " " + path);
    Set<String> names = new TreeSet<>();
    Deque<JsonElement> pending = new ArrayDeque<>(List.of(root));
    while (!pending.isEmpty()) {
      JsonElement node = pending.pop();
      if (!node.isJsonObject()) continue;
      JsonObject schema = node.getAsJsonObject();
      if (schema.has("$ref")) pending.push(resolve(schema.get("$ref").getAsString()));
      if (schema.has("properties")) names.addAll(schema.getAsJsonObject("properties").keySet());
      if (schema.has("anyOf")) schema.getAsJsonArray("anyOf").forEach(pending::push);
      for (String branch : List.of("then", "else")) {
        if (schema.has(branch)) pending.push(schema.get(branch));
      }
    }
    return names;
  }

  /**
   * Whether {@code schema} describes an object by its members and leaves other members open. Only
   * an object schema closes: a condition such as an {@code if} names properties without a type and
   * must keep matching objects that carry more.
   */
  private static boolean closesWhenStrict(JsonObject schema) {
    if (!schema.has("properties") || schema.has("additionalProperties")) return false;
    JsonElement type = schema.get("type");
    if (type == null) return false;
    if (type.isJsonArray()) {
      for (JsonElement t : type.getAsJsonArray()) if (t.getAsString().equals("object")) return true;
      return false;
    }
    return type.getAsString().equals("object");
  }

  private void check(
      JsonElement value, JsonElement schemaNode, String at, boolean closed, List<String> errors) {
    if (schemaNode.isJsonPrimitive() && schemaNode.getAsJsonPrimitive().isBoolean()) {
      if (!schemaNode.getAsBoolean()) errors.add(at + ": no value is allowed here");
      return;
    }
    JsonObject schema = schemaNode.getAsJsonObject();
    if (closed && closesWhenStrict(schema) && value.isJsonObject()) {
      JsonObject declared = schema.getAsJsonObject("properties");
      for (String name : value.getAsJsonObject().keySet()) {
        if (!declared.has(name)) {
          errors.add(at + ": property '" + name + "' is not declared by the contract");
        }
      }
    }
    for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
      String keyword = entry.getKey();
      JsonElement argument = entry.getValue();
      switch (keyword) {
        case "$ref" -> check(value, resolve(argument.getAsString()), at, closed, errors);
        case "type" -> checkType(value, argument, at, errors);
        case "enum" -> {
          boolean found = false;
          for (JsonElement option : argument.getAsJsonArray()) found |= option.equals(value);
          if (!found) errors.add(at + ": " + value + " is not one of " + argument);
        }
        case "const" -> {
          if (!argument.equals(value)) errors.add(at + ": " + value + " is not " + argument);
        }
        case "required" -> {
          if (value.isJsonObject()) {
            for (JsonElement name : argument.getAsJsonArray()) {
              if (!value.getAsJsonObject().has(name.getAsString())) {
                errors.add(at + ": required property '" + name.getAsString() + "' is missing");
              }
            }
          }
        }
        case "properties" -> {
          if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> p : argument.getAsJsonObject().entrySet()) {
              JsonElement property = value.getAsJsonObject().get(p.getKey());
              if (property != null) {
                check(property, p.getValue(), at + "." + p.getKey(), closed, errors);
              }
            }
          }
        }
        case "additionalProperties" -> {
          if (value.isJsonObject()) {
            JsonObject declared = schema.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> p : value.getAsJsonObject().entrySet()) {
              if (declared != null && declared.has(p.getKey())) continue;
              if (argument.isJsonPrimitive() && argument.getAsJsonPrimitive().isBoolean()) {
                if (!argument.getAsBoolean()) {
                  errors.add(at + ": property '" + p.getKey() + "' is not allowed by the contract");
                }
              } else {
                check(p.getValue(), argument, at + "." + p.getKey(), closed, errors);
              }
            }
          }
        }
        case "items" -> {
          if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
              check(array.get(i), argument, at + "[" + i + "]", closed, errors);
            }
          }
        }
        case "minItems" -> {
          if (value.isJsonArray() && value.getAsJsonArray().size() < argument.getAsInt()) {
            errors.add(at + ": fewer than " + argument + " items");
          }
        }
        case "pattern" -> {
          if (value.isJsonPrimitive()
              && value.getAsJsonPrimitive().isString()
              && !Pattern.compile(argument.getAsString()).matcher(value.getAsString()).find()) {
            errors.add(at + ": " + value + " does not match " + argument);
          }
        }
        case "anyOf" -> {
          boolean any = false;
          for (JsonElement branch : argument.getAsJsonArray()) {
            any |= passes(value, branch, at, closed);
          }
          if (!any) errors.add(at + ": " + value + " matches none of the anyOf branches");
        }
        case "not" -> {
          if (passes(value, argument, at, closed)) {
            errors.add(at + ": " + value + " matches a schema it must not");
          }
        }
        case "if" -> {
          JsonElement branch =
              passes(value, argument, at, closed) ? schema.get("then") : schema.get("else");
          if (branch != null) check(value, branch, at, closed, errors);
        }
        case "then", "else" -> {
          // applied by "if"
        }
        default -> {
          if (!ANNOTATIONS.contains(keyword)) {
            throw new UnsupportedOperationException(
                "The schema validator does not implement '"
                    + keyword
                    + "' (at "
                    + at
                    + "). Teach OpenApiSchemas.check the keyword rather than ignoring it.");
          }
        }
      }
    }
  }

  private boolean passes(JsonElement value, JsonElement schema, String at, boolean closed) {
    List<String> scratch = new ArrayList<>();
    check(value, schema, at, closed, scratch);
    return scratch.isEmpty();
  }

  private JsonObject resolve(String reference) {
    if (!reference.startsWith(REF_PREFIX)) {
      throw new UnsupportedOperationException("Unsupported $ref '" + reference + "'");
    }
    JsonElement target = components.get(reference.substring(REF_PREFIX.length()));
    if (target == null || !target.isJsonObject()) {
      throw new IllegalStateException("Unresolved $ref '" + reference + "'");
    }
    return target.getAsJsonObject();
  }

  private static void checkType(
      JsonElement value, JsonElement type, String at, List<String> errors) {
    List<String> accepted = new ArrayList<>();
    if (type.isJsonArray()) {
      for (JsonElement t : type.getAsJsonArray()) accepted.add(t.getAsString());
    } else {
      accepted.add(type.getAsString());
    }
    if (accepted.stream().noneMatch(t -> isType(value, t))) {
      errors.add(at + ": " + value + " is not of type " + String.join(" | ", accepted));
    }
  }

  private static boolean isType(JsonElement value, String type) {
    return switch (type) {
      case "null" -> value.isJsonNull();
      case "object" -> value.isJsonObject();
      case "array" -> value.isJsonArray();
      case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
      case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
      case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
      case "integer" ->
          value.isJsonPrimitive()
              && value.getAsJsonPrimitive().isNumber()
              && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
      default -> throw new UnsupportedOperationException("Unknown JSON Schema type '" + type + "'");
    };
  }

  private static JsonElement walk(JsonElement node, String... keys) {
    JsonElement current = node;
    for (String key : keys) {
      if (current == null || !current.isJsonObject()) return null;
      current = current.getAsJsonObject().get(key);
    }
    return current;
  }

  /** Convert the SnakeYAML object graph to a Gson tree, keeping mapping order. */
  private static JsonElement toJson(Object node) {
    if (node == null) return JsonNull.INSTANCE;
    if (node instanceof Map<?, ?> map) {
      JsonObject o = new JsonObject();
      for (Map.Entry<?, ?> e : map.entrySet())
        o.add(String.valueOf(e.getKey()), toJson(e.getValue()));
      return o;
    }
    if (node instanceof List<?> list) {
      JsonArray a = new JsonArray();
      for (Object item : list) a.add(toJson(item));
      return a;
    }
    if (node instanceof Boolean b) return new JsonPrimitive(b);
    if (node instanceof Number n) return new JsonPrimitive(n);
    if (node instanceof String s) return new JsonPrimitive(s);
    throw new UnsupportedOperationException("Unsupported YAML value " + node.getClass());
  }

  /**
   * The YAML 1.2 core-schema reading of plain scalars. SnakeYAML's default resolver follows YAML
   * 1.1, which turns plain {@code yes}, {@code no}, {@code on}, and {@code off} into booleans and
   * date-like text into timestamps. A contract enum written with those words would then compare as
   * the wrong JSON type. Quoted scalars always stay strings.
   */
  private static final class CoreSchemaResolver extends Resolver {
    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(
          Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF");
      addImplicitResolver(Tag.INT, Pattern.compile("^[-+]?[0-9]+$"), "-+0123456789");
      addImplicitResolver(
          Tag.FLOAT,
          Pattern.compile("^[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?$"),
          "-+.0123456789");
      addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL)$"), "~nN");
      addImplicitResolver(Tag.NULL, Pattern.compile("^$"), null);
    }
  }
}
