package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ezclassify.rest.contract.Change;
import com.ezclassify.rest.contract.CollectedNotice;
import com.ezclassify.rest.contract.ConceptNoticeType;
import com.ezclassify.rest.contract.DocumentAuditReport;
import com.ezclassify.rest.contract.ExplanationTrace;
import com.ezclassify.rest.contract.Fix;
import com.ezclassify.rest.contract.FixAction;
import com.ezclassify.rest.contract.GuideBreakdownEntry;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.ProcessingRole;
import com.ezclassify.rest.contract.RepairConfidence;
import com.ezclassify.rest.contract.ValidationError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the shared Context projections and the records {@link WireJson} decodes. */
class ContextJsonTest {

  @Test
  void processResultCarriesWhatWasReadBeforeRepair() {
    // The wire sends the reading only when repair changed it. A fix for a rule repair enforced
    // is applied to this list, so it has to survive decoding; absent, the component is null.
    String assertion =
        "{\"conceptId\":\"%s\",\"categoryId\":\"level\",\"confidence\":\"certain\",\"source\":\"user\"}";
    String repaired =
        "{\"assertions\":["
            + assertion.formatted("SENSITIVE")
            + "],\"assertionsBeforeRepair\":["
            + assertion.formatted("OPEN")
            + ","
            + assertion.formatted("SENSITIVE")
            + "],\"valid\":true,\"inputWasValid\":false}";
    ProcessResult read = WireJson.fromTree(Json.parseObject(repaired), ProcessResult.class);
    assertEquals(
        List.of("OPEN", "SENSITIVE"),
        read.assertionsBeforeRepair().stream().map(a -> a.conceptId()).toList());

    ProcessResult untouched =
        WireJson.fromTree(
            Json.parseObject(
                "{\"assertions\":["
                    + assertion.formatted("OPEN")
                    + "],\"valid\":true,\"inputWasValid\":true}"),
            ProcessResult.class);
    assertNull(untouched.assertionsBeforeRepair());
  }

  @Test
  void processResultCarriesOnlyTheRendersAndSuggestionsTheRequestNamed() {
    // renders holds the outputs the request named and suggestionsFor is present only when the
    // request asked for options; a result for a request that named neither has an empty map and
    // no suggestions at all.
    String named =
        "{\"rendered\":\"H\",\"valid\":true,\"inputWasValid\":true,"
            + "\"renders\":{\"marking/short\":{\"rendered\":\"(H)\"}},"
            + "\"suggestionsFor\":{\"level\":{\"suggestions\":[{\"conceptId\":\"L_H\","
            + "\"categoryId\":\"level\",\"label\":\"H\",\"selected\":true,\"order\":0}],"
            + "\"warnings\":[]}}}";
    ProcessResult asked = WireJson.fromTree(Json.parseObject(named), ProcessResult.class);
    assertEquals(List.of("marking/short"), List.copyOf(asked.renders().keySet()));
    assertEquals("L_H", asked.suggestionsFor().get("level").suggestions().get(0).conceptId());

    ProcessResult bare =
        WireJson.fromTree(
            Json.parseObject(
                "{\"rendered\":\"H\",\"valid\":true,\"inputWasValid\":true,\"renders\":{}}"),
            ProcessResult.class);
    assertTrue(bare.renders().isEmpty());
    assertNull(bare.suggestionsFor());
  }

  @Test
  void documentAuditValidationCarriesFactsRecognitionAndWhatSettlingChanged() {
    // An audit entry says whether the authored marking was right as written (inputWasValid),
    // whether it stands once settled (valid), what settling changed, and the facts it read.
    DocumentAuditReport report =
        WireJson.fromTree(
            Json.parseObject(
                "{\"wholeValidation\":{\"assertions\":[{\"categoryId\":\"level\","
                    + "\"conceptId\":\"H\",\"confidence\":\"certain\",\"source\":\"parse\"}],"
                    + "\"recognition\":{\"status\":\"complete\",\"inputFormat\":\"marking/long\","
                    + "\"role\":\"whole\",\"surface\":\"marking\",\"unmatched\":[]},"
                    + "\"valid\":true,\"inputWasValid\":false,\"errors\":[],\"proposedFixes\":[],"
                    + "\"appliedFixes\":[{\"ruleId\":\"_recognition_case\",\"action\":\"reorder\","
                    + "\"message\":\"reordered tokens\"}],\"rendered\":\"H\"}}"),
            DocumentAuditReport.class);
    var whole = report.wholeValidation();
    assertTrue(whole.valid());
    assertFalse(whole.inputWasValid());
    assertEquals("H", whole.assertions().get(0).conceptId());
    assertEquals("marking/long", whole.recognition().inputFormat());
    assertEquals(ProcessingRole.WHOLE, whole.recognition().role());
    List<Change> applied = whole.appliedFixes();
    assertEquals(1, applied.size());
    Change change = applied.get(0);
    assertEquals("_recognition_case", change.ruleId());
    assertEquals(FixAction.REORDER, change.action());
    assertEquals("reordered tokens", change.message());
    assertNull(change.add());
    assertNull(change.remove());
  }

  @Test
  void validationErrorCarriesTheConceptsAnUnreadTokenMayHaveMeant() {
    JsonArray arr =
        Json.parseArray(
            "[{\"constraintId\":\"_unmatched_token\",\"message\":\"Did you mean HIGH?\","
                + "\"didYouMean\":[\"HIGH\",\"HIGHER\"]},"
                + "{\"constraintId\":\"_recognition_incomplete\",\"message\":\"m\"}]");
    List<ValidationError> errors = ContextJson.validationErrors(arr);
    assertEquals(List.of("HIGH", "HIGHER"), errors.get(0).didYouMean());
    // Absent is not empty: the engine had no hint, which says nothing about the token.
    assertNull(errors.get(1).didYouMean());
  }

  @Test
  void fixDecodesEveryField() {
    JsonArray arr =
        Json.parseArray(
            "[{\"message\":\"replace LEVEL_HIGH\",\"rendered\":\"LEVEL-MAX\","
                + "\"keeps\":[{\"conceptId\":\"CTRL_X\",\"categoryId\":\"control\"},"
                + "{\"conceptId\":\"ORG_Y\",\"categoryId\":\"dissemination\"}],"
                + "\"adds\":[{\"conceptId\":\"LEVEL_MAX\",\"categoryId\":\"level\"}],"
                + "\"removes\":[{\"conceptId\":\"LEVEL_HIGH\",\"categoryId\":\"level\"}]}]");
    Fix f = ContextJson.fixes(arr).get(0);
    assertEquals("replace LEVEL_HIGH", f.message());
    assertEquals("LEVEL-MAX", f.rendered());
    assertEquals(2, f.keeps().size());
    assertEquals("CTRL_X", f.keeps().get(0).conceptId());
    assertEquals("control", f.keeps().get(0).categoryId());
    assertEquals("ORG_Y", f.keeps().get(1).conceptId());
    assertEquals("dissemination", f.keeps().get(1).categoryId());
    assertEquals(1, f.adds().size());
    assertEquals("LEVEL_MAX", f.adds().get(0).conceptId());
    assertEquals("level", f.adds().get(0).categoryId());
    assertEquals(1, f.removes().size());
    assertEquals("LEVEL_HIGH", f.removes().get(0).conceptId());
    assertEquals("level", f.removes().get(0).categoryId());
  }

  @Test
  void changeDecodesEachAction() {
    JsonArray arr =
        Json.parseArray(
            "["
                + "{\"ruleId\":\"infer-1\",\"action\":\"add\","
                + "\"add\":[{\"conceptId\":\"CTRL_X\",\"categoryId\":\"control\"}],"
                + "\"triggerConceptIds\":[\"CTRL_Y\"],\"message\":\"added CTRL_X\"},"
                + "{\"ruleId\":\"supersede-1\",\"action\":\"remove\","
                + "\"remove\":[{\"conceptId\":\"CTRL_X\",\"categoryId\":\"control\"}],"
                + "\"message\":\"removed CTRL_X\"},"
                + "{\"ruleId\":\"repair-1\",\"action\":\"replace\","
                + "\"add\":[{\"conceptId\":\"LEVEL_MAX\",\"categoryId\":\"level\"}],"
                + "\"remove\":[{\"conceptId\":\"LEVEL_HIGH\",\"categoryId\":\"level\"}],"
                + "\"message\":\"replaced alias\"},"
                + "{\"ruleId\":\"tolerant-1\",\"action\":\"reorder\",\"message\":\"reordered tokens\"}"
                + "]");
    List<Change> out = ContextJson.changes(arr);
    assertEquals(4, out.size());

    Change add = out.get(0);
    assertEquals("infer-1", add.ruleId());
    assertEquals(FixAction.ADD, add.action());
    assertEquals(1, add.add().size());
    assertEquals("CTRL_X", add.add().get(0).conceptId());
    assertEquals("control", add.add().get(0).categoryId());
    assertNull(add.remove());
    assertEquals(List.of("CTRL_Y"), add.triggerConceptIds());
    assertEquals("added CTRL_X", add.message());

    Change remove = out.get(1);
    assertEquals(FixAction.REMOVE, remove.action());
    assertNull(remove.add());
    assertEquals(1, remove.remove().size());
    assertEquals("CTRL_X", remove.remove().get(0).conceptId());
    assertNull(remove.triggerConceptIds());

    Change replace = out.get(2);
    assertEquals(FixAction.REPLACE, replace.action());
    assertEquals(1, replace.add().size());
    assertEquals("LEVEL_MAX", replace.add().get(0).conceptId());
    assertEquals(1, replace.remove().size());
    assertEquals("LEVEL_HIGH", replace.remove().get(0).conceptId());

    Change reorder = out.get(3);
    assertEquals(FixAction.REORDER, reorder.action());
    assertNull(reorder.add());
    assertNull(reorder.remove());
    assertNull(reorder.triggerConceptIds());
    assertEquals("reordered tokens", reorder.message());
  }

  @Test
  void unknownChangeActionKeepsItsToken() {
    // Responses are open: an action a newer server adds keeps the rest of the change readable.
    JsonArray arr =
        Json.parseArray("[{\"ruleId\":\"r\",\"action\":\"frobnicate\",\"message\":\"m\"}]");
    Change change = ContextJson.changes(arr).get(0);
    assertEquals(FixAction.Value.UNKNOWN, change.action().value());
    assertEquals("frobnicate", change.action().toWire());
    assertEquals("r", change.ruleId());
    assertEquals("m", change.message());
  }

  @Test
  void aRequiredEnumTokenThatIsMissingNullOrNotAStringIsMalformed() {
    // A required enum field without a string token is a broken response, not a value a newer
    // server added, so it must fail instead of reading as an unknown value.
    for (String token : List.of("", ",\"%s\":null", ",\"%s\":5")) {
      JsonArray warning = Json.parseArray("[{\"message\":\"w\"" + token.formatted("kind") + "}]");
      assertThrows(EzClassifyClientException.class, () -> ContextJson.warnings(warning), token);

      JsonArray change =
          Json.parseArray(
              "[{\"ruleId\":\"r\",\"message\":\"m\"" + token.formatted("action") + "}]");
      assertThrows(EzClassifyClientException.class, () -> ContextJson.changes(change), token);

      JsonObject trace =
          Json.parseObject(
              "{\"repairs\":[{\"ruleId\":\"r\",\"description\":\"d\",\"before\":\"h\","
                  + "\"after\":\"H\""
                  + token.formatted("confidence")
                  + "}]}");
      assertThrows(
          EzClassifyClientException.class, () -> ContextJson.explanationTrace(trace), token);

      JsonArray notice =
          Json.parseArray(
              "[{\"conceptId\":\"ORG\",\"conceptName\":\"O\",\"required\":true,"
                  + "\"text\":\"t\""
                  + token.formatted("type")
                  + "}]");
      assertThrows(
          EzClassifyClientException.class, () -> ContextJson.collectedNotices(notice), token);

      JsonArray assertion =
          Json.parseArray(
              "[{\"categoryId\":\"level\",\"conceptId\":\"H\",\"source\":\"parse\""
                  + token.formatted("confidence")
                  + "}]");
      assertThrows(EzClassifyClientException.class, () -> ContextJson.assertions(assertion), token);
    }
  }

  @Test
  void anAbsentNullableEnumReadsAsNullAndANonStringOneIsMalformed() {
    // Negative control for the required case: an enum the reader treats as nullable stays null
    // when absent, and still refuses a token that is not a string.
    JsonArray absent = Json.parseArray("[{\"constraintId\":\"c\",\"message\":\"m\"}]");
    assertNull(ContextJson.validationErrors(absent).get(0).severity());

    JsonArray number =
        Json.parseArray("[{\"constraintId\":\"c\",\"message\":\"m\",\"severity\":5}]");
    assertThrows(EzClassifyClientException.class, () -> ContextJson.validationErrors(number));
  }

  @Test
  void explanationTraceMapsEveryField() {
    JsonObject o =
        Json.parseObject(
            "{\"input\":\"h\",\"schemeId\":\"test-scheme\",\"rendered\":\"H\","
                + "\"entries\":[{\"stage\":\"repair\",\"action\":\"applied\",\"detail\":\"fixed case\","
                + "\"conceptId\":\"LEVEL_HIGH\",\"ruleId\":\"repair-1\",\"triggerConceptIds\":[\"t1\"]}],"
                + "\"repairs\":[{\"ruleId\":\"repair-1\",\"description\":\"case fix\",\"confidence\":\"safe\","
                + "\"before\":\"h\",\"after\":\"H\",\"cosmetic\":true}],"
                + "\"inferences\":[{\"ruleId\":\"infer-2\",\"conceptId\":\"CTRL_X\","
                + "\"conceptName\":\"Control X\",\"explanation\":\"implied\"}],"
                + "\"validationErrors\":[{\"constraintId\":\"C1\",\"message\":\"missing level\"}],"
                + "\"finalAssertions\":[{\"conceptId\":\"LEVEL_HIGH\",\"categoryId\":\"level\","
                + "\"confidence\":\"certain\",\"source\":\"parse\"}]}");
    ExplanationTrace t = ContextJson.explanationTrace(o);
    assertEquals("h", t.input());
    assertEquals("test-scheme", t.schemeId());
    assertEquals("H", t.rendered());

    assertEquals(1, t.entries().size());
    assertEquals("repair", t.entries().get(0).stage());
    assertEquals("applied", t.entries().get(0).action());
    assertEquals("fixed case", t.entries().get(0).detail());
    assertEquals("LEVEL_HIGH", t.entries().get(0).conceptId());
    assertEquals("repair-1", t.entries().get(0).ruleId());
    assertEquals(List.of("t1"), t.entries().get(0).triggerConceptIds());

    assertEquals(1, t.repairs().size());
    assertEquals("repair-1", t.repairs().get(0).ruleId());
    assertEquals("case fix", t.repairs().get(0).description());
    assertEquals(RepairConfidence.SAFE, t.repairs().get(0).confidence());
    assertEquals("h", t.repairs().get(0).before());
    assertEquals("H", t.repairs().get(0).after());
    assertEquals(Boolean.TRUE, t.repairs().get(0).cosmetic());

    assertEquals(1, t.inferences().size());
    assertEquals("infer-2", t.inferences().get(0).ruleId());
    assertEquals("CTRL_X", t.inferences().get(0).conceptId());
    assertEquals("Control X", t.inferences().get(0).conceptName());
    assertEquals("implied", t.inferences().get(0).explanation());

    assertEquals(1, t.validationErrors().size());
    assertEquals("C1", t.validationErrors().get(0).constraintId());
    assertEquals("missing level", t.validationErrors().get(0).message());

    assertEquals(1, t.finalAssertions().size());
    assertEquals("LEVEL_HIGH", t.finalAssertions().get(0).conceptId());
  }

  @Test
  void explanationTraceNullWhenAbsent() {
    assertNull(ContextJson.explanationTrace(null));
  }

  @Test
  void guideBreakdownMapsEveryField() {
    JsonArray arr =
        Json.parseArray(
            "[{\"rule\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\"},\"outcome\":{\"rendered\":\"H\"},"
                + "\"isComputed\":true,\"wasOverridden\":true,"
                + "\"overriddenBy\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\"},\"ruleId\":\"r2\",\"ruleDigest\":\"sha256:r2\"}}]");
    List<GuideBreakdownEntry> out = ContextJson.guideBreakdown(arr);
    assertEquals(1, out.size());
    GuideBreakdownEntry b = out.get(0);
    assertEquals("r1", b.rule().ruleId());
    assertEquals("H", b.outcome().rendered());
    assertTrue(b.isComputed());
    assertTrue(b.wasOverridden());
    assertEquals("r2", b.overriddenBy().ruleId());
  }

  @Test
  void guideBreakdownLeavesOptionalFieldsNull() {
    JsonArray arr =
        Json.parseArray(
            "[{\"rule\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\"},\"outcome\":{},\"isComputed\":false,\"wasOverridden\":false}]");
    GuideBreakdownEntry b = ContextJson.guideBreakdown(arr).get(0);
    assertFalse(b.isComputed());
    assertFalse(b.wasOverridden());
    assertNull(b.outcome().rendered());
    assertNull(b.overriddenBy());
  }

  @Test
  void aNoticeWhoseRequiredFlagIsNotABooleanIsRefusedRatherThanReadAsOptional() {
    JsonArray notices =
        Json.parseArray(
            "[{\"conceptId\":\"ORG\",\"conceptName\":\"Org\",\"type\":\"handling\","
                + "\"text\":\"t\",\"required\":1}]");
    assertThrows(MalformedResponseException.class, () -> ContextJson.collectedNotices(notices));
  }

  @Test
  void anAggregateWithoutTheValidFlagFailsInsteadOfReadingAsValid() {
    JsonObject aggregate = Json.parseObject("{\"rendered\":\"LEVEL-A\"}");

    EzClassifyClientException failure =
        assertThrows(EzClassifyClientException.class, () -> ContextJson.aggregateResult(aggregate));
    assertTrue(failure.getMessage().contains("valid"), failure.getMessage());
  }

  @Test
  void anAggregateCarryingTheValidFlagParses() {
    JsonObject aggregate = Json.parseObject("{\"rendered\":\"LEVEL-A\",\"valid\":false}");

    assertFalse(ContextJson.aggregateResult(aggregate).valid());
  }

  @Test
  void anAggregateCarriesTheNoticesForItsOwnAssertions() {
    JsonObject aggregate =
        Json.parseObject(
            "{\"rendered\":\"LEVEL-A\",\"valid\":true,\"notices\":[{\"conceptId\":\"ORG\","
                + "\"conceptName\":\"Organization Only\",\"type\":\"handling\","
                + "\"text\":\"handle inside the organization\",\"required\":true,"
                + "\"views\":{\"ism\":\"ORCON\",\"banner\":\"ORG ONLY\"}}]}");

    List<CollectedNotice> notices = ContextJson.aggregateResult(aggregate).notices();
    assertEquals(1, notices.size());
    assertEquals("ORG", notices.get(0).conceptId());
    assertEquals("Organization Only", notices.get(0).conceptName());
    assertEquals(ConceptNoticeType.HANDLING, notices.get(0).type());
    assertEquals("handle inside the organization", notices.get(0).text());
    assertTrue(notices.get(0).required());
    assertEquals(Map.of("ism", "ORCON", "banner", "ORG ONLY"), notices.get(0).views());
  }

  @Test
  void anAggregateWithoutNoticesReadsAsAnEmptyList() {
    JsonObject aggregate = Json.parseObject("{\"rendered\":\"LEVEL-A\",\"valid\":true}");

    assertEquals(List.of(), ContextJson.aggregateResult(aggregate).notices());
  }

  @Test
  void aMalformedResponseBodyFailsAsAProtocolError() {
    assertThrows(EzClassifyClientException.class, () -> Json.parseObject("not json"));
    assertThrows(EzClassifyClientException.class, () -> Json.parseObject("[1,2,3]"));
    assertThrows(EzClassifyClientException.class, () -> Json.parseArray("{}"));
  }
}
