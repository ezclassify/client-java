package com.ezclassify.rest.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Refuses a success body that lacks a member the contract requires.
 *
 * <p>The check walks {@link ResponseShapes}, which is generated from the OpenAPI {@code required}
 * lists, so what counts as required is the contract's answer and not this client's. It runs on
 * every success body before anything reads it: a reader that fell back to an empty list or a
 * default for an absent member would turn a malformed answer into a plausible one, and the response
 * cache would keep it. An optional member may be absent; one that is present is checked as deeply
 * as a required one when it is the container the contract names, and left to its reader when it is
 * not. A scalar of the wrong type is left to the reader that decodes it. Every refusal is a {@link
 * MalformedResponseException} whose {@link MalformedResponseException#path() path} names the
 * member, such as {@code $.result.assessment.emitted[2].conceptId}.
 */
final class ResponseCheck {
  private static final String MISSING = "is missing";

  private ResponseCheck() {}

  /** The outcome of one walk: {@code null} when the value satisfies the shape. */
  private record Failure(String suffix, String reason) {
    Failure under(String prefix) {
      return new Failure(prefix + suffix, reason);
    }
  }

  /**
   * Require a success body to have the shape its operation's contract declares.
   *
   * @param operation the operation as {@code "METHOD /path/template"}
   * @param body the parsed success body
   * @throws MalformedResponseException if the body lacks a required member at any depth, holds null
   *     where the contract does not allow it, or a required container is the wrong kind
   * @throws IllegalStateException if the contract declares no success body for the operation, which
   *     means the client sent a request the contract does not have
   */
  static void require(String operation, JsonElement body) {
    ResponseShape shape = ResponseShapes.OPERATIONS.get(operation);
    if (shape == null) {
      throw new IllegalStateException("The contract declares no success response for " + operation);
    }
    require(shape, body);
  }

  /**
   * Require a body to have one shape; the operation lookup aside, as {@link #require(String,
   * JsonElement)}.
   */
  static void require(ResponseShape shape, JsonElement body) {
    Failure failure = walk(shape, body);
    if (failure == null) return;
    String path = "$" + failure.suffix();
    String message =
        MISSING.equals(failure.reason())
            ? "Response is missing required member " + path
            : "Response member " + path + " " + failure.reason();
    throw new MalformedResponseException(message, path);
  }

  private static ResponseShape resolve(ResponseShape reference) {
    ResponseShape shape = ResponseShapes.COMPONENTS.get(reference.ref);
    if (shape == null) {
      throw new IllegalStateException(
          "The contract declares no response shape for " + reference.ref);
    }
    return shape;
  }

  private static boolean isScalar(JsonElement value) {
    return !value.isJsonObject() && !value.isJsonArray();
  }

  /** Whether the value is the container the node holds, so a walk can judge what is inside. */
  private static boolean matches(ResponseShape node, JsonElement value) {
    return switch (node.kind) {
      case REF -> matches(resolve(node), value);
      case OBJECT, MAP -> value.isJsonObject();
      case ARRAY -> value.isJsonArray();
      case ANY ->
          isScalar(value)
              ? node.acceptsScalar
              : node.alternatives.stream().anyMatch(a -> matches(a.shape(), value));
    };
  }

  private static String kindReason(ResponseShape node) {
    return switch (node.kind) {
      case REF -> kindReason(resolve(node));
      case ARRAY -> "must be an array";
      case ANY -> "does not match any shape the contract allows";
      default -> "must be an object";
    };
  }

  private static Failure walk(ResponseShape node, JsonElement value) {
    if (node.kind == ResponseShape.Kind.REF) return walk(resolve(node), value);
    if (!matches(node, value)) return new Failure("", kindReason(node));
    return switch (node.kind) {
      case OBJECT -> walkObject(node, value.getAsJsonObject());
      case ARRAY -> walkItems(node.item, value);
      case MAP -> walkEntries(node.item, value.getAsJsonObject());
      default -> walkUnion(node, value);
    };
  }

  private static Failure walkObject(ResponseShape node, JsonObject value) {
    for (String name : node.required) {
      JsonElement member = value.get(name);
      if (member != null && (!member.isJsonNull() || node.nullable.contains(name))) continue;
      return new Failure(segment(name), MISSING);
    }
    for (Map.Entry<String, ResponseShape> held : node.members.entrySet()) {
      JsonElement member = value.get(held.getKey());
      if (member == null || member.isJsonNull()) continue;
      // An optional member of the wrong kind is its reader's to tolerate; a required one was
      // promised.
      if (!matches(held.getValue(), member) && !node.required.contains(held.getKey())) continue;
      Failure inner = walk(held.getValue(), member);
      if (inner != null) return inner.under(segment(held.getKey()));
    }
    return null;
  }

  private static Failure walkItems(ResponseShape items, JsonElement array) {
    if (items == null) return null;
    int index = 0;
    for (JsonElement item : array.getAsJsonArray()) {
      Failure inner = walk(items, item);
      if (inner != null) return inner.under("[" + index + "]");
      index++;
    }
    return null;
  }

  private static Failure walkEntries(ResponseShape values, JsonObject map) {
    for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
      Failure inner = walk(values, entry.getValue());
      if (inner != null) return inner.under(segment(entry.getKey()));
    }
    return null;
  }

  private enum Named {
    YES,
    NO,
    ABSENT
  }

  /**
   * A discriminated alternative is judged only when the value names it. A value that names none of
   * them is a variant this client has not met, which an open-response contract leaves to the
   * reader; a value that names no discriminator at all is tried against every alternative, each of
   * which requires its own.
   */
  private static Failure walkUnion(ResponseShape node, JsonElement value) {
    if (isScalar(value)) return null;
    List<ResponseShape.Alternative> candidates = new ArrayList<>();
    for (ResponseShape.Alternative alternative : node.alternatives) {
      if (matches(alternative.shape(), value)) candidates.add(alternative);
    }
    List<ResponseShape.Alternative> named =
        candidates.stream().filter(a -> discrimination(a, value) == Named.YES).toList();
    if (!named.isEmpty()) {
      candidates = named;
    } else {
      candidates =
          candidates.stream()
              .filter(a -> a.discriminators().isEmpty() || discrimination(a, value) == Named.ABSENT)
              .toList();
      if (candidates.isEmpty()) return null;
    }
    Failure first = null;
    for (ResponseShape.Alternative candidate : candidates) {
      Failure failure = walk(candidate.shape(), value);
      if (failure == null) return null;
      if (first == null) first = failure;
    }
    return first;
  }

  private static Named discrimination(ResponseShape.Alternative alternative, JsonElement value) {
    if (alternative.discriminators().isEmpty() || !value.isJsonObject()) return Named.NO;
    JsonObject object = value.getAsJsonObject();
    boolean absent = false;
    for (Map.Entry<String, String> expected : alternative.discriminators().entrySet()) {
      JsonElement member = object.get(expected.getKey());
      if (member == null || !member.isJsonPrimitive() || !member.getAsJsonPrimitive().isString()) {
        absent = true;
        continue;
      }
      if (!member.getAsString().equals(expected.getValue())) return Named.NO;
    }
    return absent ? Named.ABSENT : Named.YES;
  }

  private static String segment(String name) {
    for (char c : name.toCharArray()) {
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        return "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
      }
    }
    return "." + name;
  }
}
