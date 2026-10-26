package com.ezclassify.rest.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One node of a response shape: the container a JSON value must be and, for an object, the members
 * it must carry. The generated {@link ResponseShapes} table is built from these factories; nothing
 * else constructs them.
 */
final class ResponseShape {
  enum Kind {
    REF,
    OBJECT,
    ARRAY,
    MAP,
    ANY
  }

  /** One alternative of a union, with the string members that pick it out. */
  record Alternative(ResponseShape shape, Map<String, String> discriminators) {}

  final Kind kind;

  /** The component a {@link Kind#REF} names. */
  final String ref;

  /** The members an object must carry, in the order the contract lists them. */
  final List<String> required;

  /** The required members whose value may be JSON null. */
  final Set<String> nullable;

  /** The members that hold a container, with the shape each holds. */
  final Map<String, ResponseShape> members;

  /** The shape of an array's items or a map's values; {@code null} for scalar items. */
  final ResponseShape item;

  /** Whether a union also admits a scalar. */
  final boolean acceptsScalar;

  final List<Alternative> alternatives;

  private ResponseShape(
      Kind kind,
      String ref,
      List<String> required,
      Set<String> nullable,
      Map<String, ResponseShape> members,
      ResponseShape item,
      boolean acceptsScalar,
      List<Alternative> alternatives) {
    this.kind = kind;
    this.ref = ref;
    this.required = required;
    this.nullable = nullable;
    this.members = members;
    this.item = item;
    this.acceptsScalar = acceptsScalar;
    this.alternatives = alternatives;
  }

  static ResponseShape ref(String component) {
    return new ResponseShape(Kind.REF, component, List.of(), Set.of(), Map.of(), null, false, null);
  }

  /**
   * An object node. {@code required} and {@code nullable} are space-separated member names, and
   * {@code members} alternates a member name with the shape that member holds.
   */
  static ResponseShape object(String required, String nullable, Object... members) {
    Map<String, ResponseShape> held = new LinkedHashMap<>();
    for (int i = 0; i < members.length; i += 2) {
      held.put((String) members[i], (ResponseShape) members[i + 1]);
    }
    return new ResponseShape(
        Kind.OBJECT, null, names(required), Set.copyOf(names(nullable)), held, null, false, null);
  }

  static ResponseShape array(ResponseShape items) {
    return new ResponseShape(Kind.ARRAY, null, List.of(), Set.of(), Map.of(), items, false, null);
  }

  static ResponseShape map(ResponseShape values) {
    return new ResponseShape(Kind.MAP, null, List.of(), Set.of(), Map.of(), values, false, null);
  }

  static ResponseShape any(boolean acceptsScalar, Alternative... alternatives) {
    return new ResponseShape(
        Kind.ANY, null, List.of(), Set.of(), Map.of(), null, acceptsScalar, List.of(alternatives));
  }

  /** A union alternative; {@code discriminators} alternates a member name with its constant. */
  static Alternative alternative(ResponseShape shape, String... discriminators) {
    Map<String, String> named = new LinkedHashMap<>();
    for (int i = 0; i < discriminators.length; i += 2) {
      named.put(discriminators[i], discriminators[i + 1]);
    }
    return new Alternative(shape, named);
  }

  private static List<String> names(String spaced) {
    if (spaced.isEmpty()) return List.of();
    return new ArrayList<>(List.of(spaced.split(" ")));
  }
}
