package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.DocumentAuditReport;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.Reproducibility;
import com.ezclassify.rest.contract.SourceReference;
import com.ezclassify.rest.contract.ValidateResult;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract enum and required-member handling in the shared Gson configuration. */
class WireJsonTest {

  @Test
  void anUnknownTokenSurvivesDecodeAndReEncodeByteForByte() {
    String wire =
        "{\"reproducibility\":\"pinned\",\"snapshotId\":\"snap-1\",\"providerId\":\"idp\"}";

    SourceReference decoded =
        WireJson.fromTree(JsonParser.parseString(wire), SourceReference.class);

    assertFalse(decoded.reproducibility().isKnown());
    assertEquals(wire, WireJson.toTree(decoded).toString());
  }

  @Test
  void aDeclaredTokenDecodesToTheCanonicalConstant() {
    // Negative control for the unknown case: a declared token is the shared constant.
    SourceReference decoded =
        WireJson.fromTree(
            JsonParser.parseString("{\"reproducibility\":\"best-effort\"}"), SourceReference.class);

    assertSame(Reproducibility.BEST_EFFORT, decoded.reproducibility());
  }

  @Test
  void aNumberInsideAnUntypedValueKeepsItsLiteral() {
    // Assertion metadata is untyped; an integer must not come back as a double.
    String wire =
        "{\"categoryId\":\"c\",\"conceptId\":\"k\",\"confidence\":\"certain\","
            + "\"metadata\":{\"n\":3,\"big\":12345678901,\"d\":1.50,\"list\":[1,2.5]},"
            + "\"source\":\"parse\"}";

    Assertion decoded = WireJson.fromTree(JsonParser.parseString(wire), Assertion.class);

    assertEquals(Integer.valueOf(3), decoded.metadata().get("n"));
    assertEquals(wire, WireJson.toTree(decoded).toString());
  }

  @Test
  void aContractEnumTokenThatIsNotAStringIsMalformed() {
    // Gson's nextString() would turn 5 into the token "5"; a contract enum is always a string.
    assertThrows(
        JsonParseException.class,
        () ->
            WireJson.fromTree(
                JsonParser.parseString("{\"reproducibility\":5}"), SourceReference.class));
  }

  @Test
  void aResultWithoutItsVerdictIsMalformedRatherThanReadAsFalse() {
    // valid and inputWasValid are required booleans. Java has no absent boolean, so without this a
    // response that left one out would read as not valid, or as wrong as written.
    String complete = "{\"rendered\":\"H\",\"valid\":true,\"inputWasValid\":false}";
    assertFalse(
        WireJson.fromTree(JsonParser.parseString(complete), ValidateResult.class).inputWasValid());
    assertTrue(WireJson.fromTree(JsonParser.parseString(complete), ProcessResult.class).valid());

    for (String verdict : List.of("valid", "inputWasValid")) {
      JsonObject missing = JsonParser.parseString(complete).getAsJsonObject();
      missing.remove(verdict);
      JsonObject nulled = JsonParser.parseString(complete).getAsJsonObject();
      nulled.add(verdict, JsonNull.INSTANCE);
      for (JsonObject result : List.of(missing, nulled)) {
        JsonParseException refused =
            assertThrows(
                JsonParseException.class, () -> WireJson.fromTree(result, ValidateResult.class));
        assertTrue(refused.getMessage().contains(verdict), refused.getMessage());
        assertThrows(
            JsonParseException.class, () -> WireJson.fromTree(result, ProcessResult.class));
      }
      // The check holds wherever the record appears, such as an audit entry inside a report.
      JsonObject audit = new JsonObject();
      audit.add("wholeValidation", missing);
      assertThrows(
          JsonParseException.class, () -> WireJson.fromTree(audit, DocumentAuditReport.class));
    }
  }
}
