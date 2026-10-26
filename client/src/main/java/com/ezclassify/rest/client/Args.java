package com.ezclassify.rest.client;

import java.util.List;

/**
 * Argument guards for the public Context operation surface.
 *
 * <p>Every Context operation is a boundary: a missing argument is a caller mistake and should be
 * reported as one, at the call, rather than becoming an empty request body the server rejects or a
 * {@code NullPointerException} thrown from inside the request builder.
 */
final class Args {

  private Args() {}

  /**
   * Require a present value. Empty text is left alone: several operations treat an empty marking or
   * an empty query as a meaningful input.
   */
  static <T> T required(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " must not be null");
    return value;
  }

  /** Require a present, non-blank identifier. */
  static String requiredId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    return value;
  }

  /** Require a present list. An empty list is a legitimate payload; a null list is not. */
  static <T> List<T> requiredList(List<T> value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " must not be null");
    return value;
  }
}
