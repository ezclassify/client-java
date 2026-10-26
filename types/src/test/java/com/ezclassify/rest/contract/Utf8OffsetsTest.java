package com.ezclassify.rest.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Utf8OffsetsTest {
  @Test
  void convertsAsciiOffsetsInBothDirections() {
    assertEquals(3, Utf8Offsets.toStringIndex("abc\r\ndef", 3));
    assertEquals(5, Utf8Offsets.toByteOffset("abc\r\ndef", 5));
  }

  @Test
  void convertsNonBmpAndCombiningSequencesWithoutNormalizingText() {
    String text = "A\uD83D\uDE80e\u0301Z";

    assertEquals(1, Utf8Offsets.toStringIndex(text, 1));
    assertEquals(3, Utf8Offsets.toStringIndex(text, 5));
    assertEquals(4, Utf8Offsets.toStringIndex(text, 6));
    assertEquals(8, Utf8Offsets.toByteOffset(text, 5));
  }

  @Test
  void rejectsOffsetsThatSplitEncodedCodePointsOrSurrogatePairs() {
    String text = "A\uD83D\uDE80Z";

    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.toStringIndex(text, 2));
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.toByteOffset(text, 2));
  }

  @Test
  void rejectsUnpairedSurrogatesAtTheBoundary() {
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.requireValidUnicode("\uD800"));
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.requireValidUnicode("\uDC00"));
  }

  @Test
  void batchConversionMatchesTheSingleOffsetPathOnEveryBoundary() {
    String text = "a\u00e9\u4e2d\ud83d\ude00z";
    int byteLength = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    java.util.List<Integer> boundaries = new java.util.ArrayList<>();
    for (int offset = 0; offset <= byteLength; offset++) {
      try {
        Utf8Offsets.toStringIndex(text, offset);
        boundaries.add(offset);
      } catch (IllegalArgumentException notABoundary) {
        // Interior bytes of a multi-byte code point are deliberately not convertible.
      }
    }
    int[] offsets = boundaries.stream().mapToInt(Integer::intValue).toArray();
    int[] batch = Utf8Offsets.toStringIndices(text, offsets);
    for (int i = 0; i < offsets.length; i++) {
      assertEquals(Utf8Offsets.toStringIndex(text, offsets[i]), batch[i]);
    }
    int[] back = Utf8Offsets.toByteOffsets(text, batch);
    assertArrayEquals(offsets, back);
  }

  @Test
  void batchConversionAnswersInTheOrderTheOffsetsWereGiven() {
    String text = "a\u00e9b";
    assertArrayEquals(new int[] {3, 0, 1}, Utf8Offsets.toStringIndices(text, 4, 0, 1));
    assertArrayEquals(new int[] {4, 0, 1}, Utf8Offsets.toByteOffsets(text, 3, 0, 1));
  }

  @Test
  void batchConversionIsEmptyForNoOffsets() {
    assertArrayEquals(new int[0], Utf8Offsets.toStringIndices("abc"));
    assertArrayEquals(new int[0], Utf8Offsets.toByteOffsets("abc"));
  }

  @Test
  void batchConversionRejectsTheSameOffsetsTheSingleOffsetPathRejects() {
    String text = "a\u00e9b";
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.toStringIndices(text, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.toStringIndices(text, 0, 2));
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.toStringIndices(text, 0, 99));
    assertThrows(IllegalArgumentException.class, () -> Utf8Offsets.toByteOffsets(text, 0, 99));
    assertThrows(
        IllegalArgumentException.class, () -> Utf8Offsets.toStringIndices("\ud83d\ude00", 0, 1, 2));
    assertThrows(
        IllegalArgumentException.class, () -> Utf8Offsets.toByteOffsets("\ud83d\ude00", 1));
  }
}
