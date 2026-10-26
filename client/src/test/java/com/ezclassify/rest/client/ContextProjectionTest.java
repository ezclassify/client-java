package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ezclassify.rest.contract.AggregateResult;
import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.CollectedNotice;
import com.ezclassify.rest.contract.ExplanationTrace;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Context projections bind wire names and retain their defaults without positional constructors.
 */
class ContextProjectionTest {
  @Test
  void assertionFieldsBindByNameAndUntypedMetadataStaysUntyped() {
    JsonObject wire =
        Json.parseObject(
            """
        {"value":"v","sourceRule":"r","source":"caller","replacedConceptId":"old",
         "provenance":{"providerId":"provider","reproducibility":"future-token"},
         "metadata":{"conceptId":7,"source":false,"nested":[null,{"confidence":3}]},
         "expiresAt":"tomorrow","confidence":"future-confidence",
         "conceptId":"concept","categoryId":"category","futureMember":true}
        """);
    JsonObject original = wire.deepCopy();
    Assertion assertion = ContextJson.assertions(array(wire)).get(0);
    assertEquals("category", assertion.categoryId());
    assertEquals("concept", assertion.conceptId());
    assertEquals("future-confidence", assertion.confidence().toWire());
    assertEquals("tomorrow", assertion.expiresAt());
    assertEquals("old", assertion.replacedConceptId());
    assertEquals("caller", assertion.source());
    assertEquals("r", assertion.sourceRule());
    assertEquals("v", assertion.value());
    assertEquals("future-token", assertion.provenance().reproducibility().toWire());
    assertNull(assertion.provenance().snapshotId());
    assertNull(assertion.provenance().attribution());
    assertEquals(Integer.valueOf(7), assertion.metadata().get("conceptId"));
    assertEquals(Boolean.FALSE, assertion.metadata().get("source"));
    List<?> nested = (List<?>) assertion.metadata().get("nested");
    assertNull(nested.get(0));
    assertEquals(Map.of("confidence", Integer.valueOf(3)), nested.get(1));
    assertEquals(original, wire);
  }

  @Test
  void absentAndNullRequiredValuesKeepProjectionDefaultsWithoutFillingOptionalValues() {
    for (String extras : List.of("", ",\"categoryId\":null,\"source\":null,\"value\":null")) {
      Assertion assertion =
          ContextJson.assertions(Json.parseArray("[{\"confidence\":\"certain\"" + extras + "}]"))
              .get(0);
      assertEquals("", assertion.categoryId());
      assertEquals("", assertion.conceptId());
      assertEquals("", assertion.source());
      assertNull(assertion.value());
      assertNull(assertion.metadata());
      assertNull(assertion.provenance());
    }
    AggregateResult result =
        ContextJson.aggregateResult(
            Json.parseObject(
                "{\"valid\":false,\"rendered\":null,\"assertions\":null,\"displayColor\":{}}"));
    assertEquals("", result.rendered());
    assertEquals(List.of(), result.assertions());
    assertEquals(List.of(), result.errors());
    assertEquals(List.of(), result.warnings());
    assertEquals(List.of(), result.notices());
    assertEquals(List.of(), result.appliedFixes());
    assertEquals(List.of(), result.proposedFixes());
    assertNull(result.displayColor().text());
    assertNull(result.displayColor().background());
    ExplanationTrace trace = ContextJson.explanationTrace(new JsonObject());
    assertEquals("", trace.input());
    assertEquals("", trace.schemeId());
    assertEquals("", trace.rendered());
    assertEquals(List.of(), trace.entries());
    assertEquals(List.of(), trace.repairs());
    assertEquals(List.of(), trace.inferences());
    assertEquals(List.of(), trace.validationErrors());
    assertEquals(List.of(), trace.finalAssertions());
  }

  @Test
  void optionalCollectionsDistinguishAbsentNullAndEmpty() {
    for (String fields : List.of("", ",\"didYouMean\":null,\"affectedConcepts\":null")) {
      var error =
          ContextJson.validationErrors(
                  Json.parseArray("[{\"message\":\"m\",\"constraintId\":\"c\"" + fields + "}]"))
              .get(0);
      assertNull(error.didYouMean());
      assertNull(error.affectedConcepts());
    }
    var error =
        ContextJson.validationErrors(
                Json.parseArray(
                    "[{\"message\":\"m\",\"constraintId\":\"c\",\"didYouMean\":[],\"affectedConcepts\":[]}]"))
            .get(0);
    assertEquals(List.of(), error.didYouMean());
    assertEquals(List.of(), error.affectedConcepts());
    assertThrows(UnsupportedOperationException.class, () -> error.didYouMean().add("x"));
  }

  @Test
  void stringCollectionsAndOptionalBooleanKeepTheirProjectionRules() {
    var error =
        ContextJson.validationErrors(
                Json.parseArray(
                    "[{\"didYouMean\":[null,7,false,{\"x\":1}],\"message\":{},\"constraintId\":7}]"))
            .get(0);
    assertEquals(List.of("", "7", "false", "{\"x\":1}"), error.didYouMean());
    assertEquals("{}", error.message());
    assertEquals("7", error.constraintId());
    CollectedNotice notice =
        ContextJson.collectedNotices(
                Json.parseArray(
                    """
        [{"required":false,"type":"future-type","views":{"a":null,"b":7,"c":{}},
          "conceptId":"id","conceptName":"name","text":"text"}]
        """))
            .get(0);
    assertNull(notice.views().get("a"));
    assertEquals("7", notice.views().get("b"));
    assertEquals("{}", notice.views().get("c"));
    for (String flag : List.of("true", "\"true\"", "7", "{}", "null")) {
      var trace =
          ContextJson.explanationTrace(
              Json.parseObject(
                  "{\"repairs\":[{\"confidence\":\"safe\",\"cosmetic\":" + flag + "}]}"));
      Boolean expected =
          flag.equals("null") ? null : flag.equals("true") || flag.equals("\"true\"");
      assertEquals(expected, trace.repairs().get(0).cosmetic());
    }
  }

  @Test
  void nestedProjectionListsAreImmutableAndFixReferencesStillRequireArrays() {
    var trace =
        ContextJson.explanationTrace(
            Json.parseObject(
                """
        {"entries":[{"triggerConceptIds":["id"]}],"finalAssertions":[{"confidence":"certain"}]}
        """));
    assertThrows(UnsupportedOperationException.class, () -> trace.entries().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> trace.entries().get(0).triggerConceptIds().clear());
    assertThrows(UnsupportedOperationException.class, () -> trace.finalAssertions().clear());
    for (String missing : List.of("keeps", "adds", "removes")) {
      JsonObject fix = Json.parseObject("{\"keeps\":[],\"adds\":[],\"removes\":[]}");
      fix.remove(missing);
      assertThrows(MalformedResponseException.class, () -> ContextJson.fixes(array(fix)));
    }
  }

  @Test
  void ordinaryWireDecodingRetainsItsOwnAbsencePolicy() {
    Assertion decoded =
        WireJson.fromTree(Json.parseObject("{\"confidence\":\"certain\"}"), Assertion.class);
    assertNull(decoded.categoryId());
    assertNull(decoded.source());
    // The boundary shape checker, rather than a context default, refuses incomplete responses.
    assertThrows(
        MalformedResponseException.class,
        () ->
            ResponseCheck.require(
                "POST /v1/ctx/{ctxId}/{version}/scheme/explain", new JsonObject()));
  }

  private static com.google.gson.JsonArray array(JsonObject object) {
    var array = new com.google.gson.JsonArray();
    array.add(object);
    return array;
  }
}
