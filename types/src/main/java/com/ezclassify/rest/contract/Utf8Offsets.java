package com.ezclassify.rest.contract;

/** Utilities for translating frozen UTF-8 byte spans to Java UTF-16 string indices. */
public final class Utf8Offsets {
  private Utf8Offsets() {}

  /**
   * Rejects an unpaired UTF-16 surrogate before a document is transported or hashed.
   *
   * @param text native Java string to validate
   * @throws IllegalArgumentException when {@code text} is null or is not a Unicode scalar sequence
   */
  public static void requireValidUnicode(String text) {
    if (text == null) throw new IllegalArgumentException("text must not be null");
    for (int i = 0; i < text.length(); i++) {
      char current = text.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
          throw new IllegalArgumentException("invalid-unicode");
        }
        i++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("invalid-unicode");
      }
    }
  }

  /**
   * Converts a zero-based UTF-8 byte offset to a Java UTF-16 string index.
   *
   * @param text exact document string
   * @param byteOffset zero-based byte offset on a code-point boundary
   * @return corresponding Java string index
   * @throws IllegalArgumentException for invalid Unicode, out-of-range offsets, or mid-code-point
   *     offsets
   * @see #toStringIndices(String, int...) for mapping many offsets on one document without
   *     rescanning it per offset
   */
  public static int toStringIndex(String text, int byteOffset) {
    requireValidUnicode(text);
    if (byteOffset < 0) throw new IllegalArgumentException("byte offset must be non-negative");
    int bytes = 0;
    for (int index = 0; index < text.length(); ) {
      if (bytes == byteOffset) return index;
      int codePoint = text.codePointAt(index);
      bytes += utf8Length(codePoint);
      if (bytes > byteOffset) {
        throw new IllegalArgumentException("byte offset is not on a UTF-8 code-point boundary");
      }
      index += Character.charCount(codePoint);
    }
    if (bytes == byteOffset) return text.length();
    throw new IllegalArgumentException("byte offset exceeds the UTF-8 document length");
  }

  /**
   * Converts many UTF-8 byte offsets on one document in a single pass.
   *
   * <p>{@link #toStringIndex(String, int)} validates the document and rescans it from the start for
   * every call, so mapping the spans an audit or access-assessment response carries costs work
   * proportional to the span count times the document length. This entry point validates once and
   * walks the document once.
   *
   * @param text exact document string
   * @param byteOffsets zero-based byte offsets, in any order, each on a code-point boundary
   * @return the corresponding Java string indices, in the order the offsets were given
   * @throws IllegalArgumentException for invalid Unicode, out-of-range offsets, or mid-code-point
   *     offsets
   */
  public static int[] toStringIndices(String text, int... byteOffsets) {
    requireValidUnicode(text);
    if (byteOffsets == null) throw new IllegalArgumentException("byteOffsets must not be null");
    Integer[] order = ascendingOrder(byteOffsets);
    int[] result = new int[byteOffsets.length];
    int next = 0;
    int bytes = 0;
    int index = 0;
    while (next < order.length) {
      int target = byteOffsets[order[next]];
      if (target < 0) throw new IllegalArgumentException("byte offset must be non-negative");
      if (bytes == target) {
        result[order[next]] = index;
        next++;
        continue;
      }
      if (index >= text.length()) {
        throw new IllegalArgumentException("byte offset exceeds the UTF-8 document length");
      }
      int codePoint = text.codePointAt(index);
      bytes += utf8Length(codePoint);
      if (bytes > target) {
        throw new IllegalArgumentException("byte offset is not on a UTF-8 code-point boundary");
      }
      index += Character.charCount(codePoint);
    }
    return result;
  }

  /**
   * Converts many Java UTF-16 string indices on one document in a single pass.
   *
   * <p>The batch counterpart of {@link #toByteOffset(String, int)}, with the same one-validation,
   * one-walk cost as {@link #toStringIndices(String, int...)}.
   *
   * @param text exact document string
   * @param stringIndices Java string indices, in any order, each on a code-point boundary
   * @return the corresponding UTF-8 byte offsets, in the order the indices were given
   * @throws IllegalArgumentException for invalid Unicode or a non-boundary index
   */
  public static int[] toByteOffsets(String text, int... stringIndices) {
    requireValidUnicode(text);
    if (stringIndices == null) throw new IllegalArgumentException("stringIndices must not be null");
    for (int stringIndex : stringIndices) requireIndexOnBoundary(text, stringIndex);
    Integer[] order = ascendingOrder(stringIndices);
    int[] result = new int[stringIndices.length];
    int next = 0;
    int bytes = 0;
    int index = 0;
    while (next < order.length) {
      int target = stringIndices[order[next]];
      if (index == target) {
        result[order[next]] = bytes;
        next++;
        continue;
      }
      int codePoint = text.codePointAt(index);
      bytes += utf8Length(codePoint);
      index += Character.charCount(codePoint);
    }
    return result;
  }

  /** Indices into {@code values}, ordered by ascending value, so one walk can serve them all. */
  private static Integer[] ascendingOrder(int[] values) {
    Integer[] order = new Integer[values.length];
    for (int i = 0; i < values.length; i++) order[i] = i;
    java.util.Arrays.sort(order, (a, b) -> Integer.compare(values[a], values[b]));
    return order;
  }

  /**
   * Converts a Java UTF-16 string index to a zero-based UTF-8 byte offset.
   *
   * @param text exact document string
   * @param stringIndex Java string index on a code-point boundary
   * @return corresponding UTF-8 byte offset
   * @throws IllegalArgumentException for invalid Unicode or a non-boundary index
   */
  public static int toByteOffset(String text, int stringIndex) {
    requireValidUnicode(text);
    requireIndexOnBoundary(text, stringIndex);
    int bytes = 0;
    for (int index = 0; index < stringIndex; ) {
      int codePoint = text.codePointAt(index);
      bytes += utf8Length(codePoint);
      index += Character.charCount(codePoint);
    }
    return bytes;
  }

  /** In range and not between the halves of a surrogate pair. */
  private static void requireIndexOnBoundary(String text, int stringIndex) {
    if (stringIndex < 0 || stringIndex > text.length()) {
      throw new IllegalArgumentException("string index is outside the document");
    }
    if (stringIndex > 0
        && stringIndex < text.length()
        && Character.isHighSurrogate(text.charAt(stringIndex - 1))
        && Character.isLowSurrogate(text.charAt(stringIndex))) {
      throw new IllegalArgumentException("string index splits a surrogate pair");
    }
  }

  private static int utf8Length(int codePoint) {
    if (codePoint <= 0x7f) return 1;
    if (codePoint <= 0x7ff) return 2;
    if (codePoint <= 0xffff) return 3;
    return 4;
  }
}
