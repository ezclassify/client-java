package com.ezclassify.rest.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Codec contract for {@link FixAction}: the wire tokens are the four lowercase strings, strict
 * decoding throws on an undeclared token, and {@code toWire}/{@code fromWire} round-trip every
 * declared value. {@link WireEnumContractTest} covers the lenient decoding every contract enum
 * shares.
 */
class FixActionTest {

  @Test
  void wireTokensMatchTheDeclaredValues() {
    assertEquals("add", FixAction.ADD.toWire());
    assertEquals("remove", FixAction.REMOVE.toWire());
    assertEquals("replace", FixAction.REPLACE.toWire());
    assertEquals("reorder", FixAction.REORDER.toWire());
  }

  @Test
  void fromWireRoundTripsEveryValue() {
    assertEquals(4, FixAction.values().size());
    for (FixAction a : FixAction.values()) {
      assertSame(a, FixAction.fromWire(a.toWire()));
    }
  }

  @Test
  void fromWireIsNullSafe() {
    assertNull(FixAction.fromWire(null));
  }

  @Test
  void fromWireRejectsUnknownToken() {
    assertThrows(IllegalArgumentException.class, () -> FixAction.fromWire("frobnicate"));
    // Wire is the lowercase token; the SCREAMING_CASE Java name is not a valid wire token.
    assertThrows(IllegalArgumentException.class, () -> FixAction.fromWire("ADD"));
  }
}
