package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Json#toNumber(String)} literal-to-type mapping. The conformance recording-key
 * hashes are computed over the string forms of these values, so every branch here is part of the
 * pinned test contract.
 */
class JsonTest {

  @Test
  void integralLiteralsNarrowToIntegerThenLongThenBigInteger() {
    assertEquals(Integer.valueOf(0), Json.toNumber("0"));
    assertEquals(Integer.valueOf(42), Json.toNumber("42"));
    assertEquals(Integer.valueOf(-7), Json.toNumber("-7"));
    assertEquals(Long.valueOf(123456789012L), Json.toNumber("123456789012"));
    assertEquals(new BigInteger("123456789012345678901"), Json.toNumber("123456789012345678901"));
  }

  @Test
  void decimalAndExponentLiteralsKeepSourceRepresentation() {
    assertEquals(new BigDecimal("1.50"), Json.toNumber("1.50"));
    assertEquals("1.50", Json.toNumber("1.50").toString());
    assertEquals(new BigDecimal("2.5"), Json.toNumber("2.5"));
    assertEquals(new BigDecimal("1e10"), Json.toNumber("1e10"));
  }

  @Test
  void minusSignedZeroDecimalLiteralsMapToNegativeZeroDouble() {
    assertEquals(Double.valueOf(-0.0d), Json.toNumber("-0.0"));
    assertEquals(Double.valueOf(-0.0d), Json.toNumber("-0e0"));
    assertEquals(Double.valueOf(-0.0d), Json.toNumber("-0.000"));
  }

  @Test
  void bareMinusZeroLiteralIsDecimalNotation() {
    // The exact literal "-0" maps to Double -0.0 and positive zero stays on the integer path, so
    // both re-serialize to the strings the shared recording corpus hashed into its keys.
    Number n = Json.toNumber("-0");
    assertEquals(Double.class, n.getClass());
    assertEquals(Double.valueOf(-0.0d), n);
    assertEquals(Integer.valueOf(0), Json.toNumber("0"));
  }
}
