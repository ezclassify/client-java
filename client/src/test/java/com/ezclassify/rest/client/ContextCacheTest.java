package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.AssertionConfidence;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.ProcessingContext;
import com.ezclassify.rest.contract.ProcessingRole;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Wire-count regressions for Context response reuse and audited decisions. */
class ContextCacheTest {
  private static final String PREFIX = "/v1/ctx/cache-test/1.0.0";
  private static final String RESOLUTION =
      "\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[]";
  private static final String SETTLED_H =
      "\"assertions\":[],\"valid\":true,\"inputWasValid\":true,\"errors\":[],\"warnings\":[],"
          + "\"appliedFixes\":[],\"rendered\":\"H\",\"assessment\":{\"emitted\":[],\"omitted\":[],"
          + "\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]},\"notices\":[]";
  private static final String VALIDATE_ENVELOPE =
      "{" + RESOLUTION + ",\"result\":{" + SETTLED_H + "}}";
  private static final String PROCESS_ENVELOPE =
      "{" + RESOLUTION + ",\"result\":{" + SETTLED_H + ",\"proposedFixes\":[],\"renders\":{}}}";
  private static final String DECISION =
      "{\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":{\"granted\":true,\"rules\":[]}}";
  private static final String TRACE_ENVELOPE =
      "{\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":{\"input\":\"H\",\"schemeId\":\"s\",\"entries\":[],\"repairs\":[],\"validationErrors\":[],\"inferences\":[],\"finalAssertions\":[],\"rendered\":\"H\"}}";
  private static final String SUGGESTIONS_ENVELOPE =
      "{\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":{\"level\":{\"suggestions\":[],\"warnings\":[]}}}";
  private static final Assertion LEVEL =
      new Assertion(
          "level", "H", AssertionConfidence.CERTAIN, null, null, null, null, "user", null, null);
  private static final String CUI_PREFIX = "/v1/ctx/us-gov-cui/20260720.0.0";
  private static final String CUI_ENVELOPE =
      "{\"resolution\":{\"contextId\":\"us-gov-cui\",\"resolvedVersion\":\"20260720.0.0\",\"artifacts\":[],\"sources\":[]},"
          + "\"operationLimitations\":[],\"result\":{\"valid\":true,\"rendered\":\"CUI\",\"inputWasValid\":true,"
          + "\"assertions\":[{\"categoryId\":\"cui_designator\",\"conceptId\":\"CUI\",\"confidence\":\"certain\","
          + "\"source\":\"parse\"}],\"errors\":[],\"warnings\":[],\"appliedFixes\":[],\"proposedFixes\":[],"
          + "\"assessment\":{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]},"
          + "\"renders\":{},\"notices\":[]}}";
  private static final String CUI_CONCEPT_ENVELOPE =
      "{\"resolution\":{\"contextId\":\"us-gov-cui\",\"resolvedVersion\":\"20260720.0.0\",\"artifacts\":[],\"sources\":[]},"
          + "\"operationLimitations\":[],\"result\":{\"conceptId\":\"CUI\",\"categoryId\":\"cui_designator\",\"name\":\"CUI\","
          + "\"lines\":[\"CUI\"],\"rules\":[]}}";
  private static final String CUI_ACCESS_RULES =
      "{\"rules\":[{\"rule\":{\"artifact\":{\"kind\":\"access\",\"id\":\"cui-access\",\"version\":\"1.0.0\"},"
          + "\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\"},"
          + "\"access\":{\"kind\":\"access\",\"id\":\"cui-access\",\"version\":\"1.0.0\"},"
          + "\"scheme\":{\"kind\":\"scheme\",\"id\":\"us-gov-cui\",\"version\":\"20260720.0.0\"},"
          + "\"name\":\"CUI rule\",\"requirementText\":\"Agency staff only\",\"attributesNeeded\":[\"agency\"],"
          + "\"dependsOn\":[],\"overrides\":[],\"lines\":[]}]}";

  @Test
  void everyCannedAnswerIsOneTheContractAllows() {
    // Checked as ContextRemoteTest checks its stubs, with every object closed, so the wire counts
    // below are measured against answers a server could send.
    OpenApiSchemas spec = OpenApiSchemas.load();
    List<String[]> answers =
        List.of(
            new String[] {"scheme/process", PROCESS_ENVELOPE},
            new String[] {"scheme/validate", VALIDATE_ENVELOPE},
            new String[] {"scheme/explain", TRACE_ENVELOPE},
            new String[] {"scheme/suggest-for-categories", SUGGESTIONS_ENVELOPE},
            new String[] {"access/check", DECISION},
            new String[] {"scheme/process", CUI_ENVELOPE},
            new String[] {"scheme/describe-concept", CUI_CONCEPT_ENVELOPE},
            new String[] {"access/rules", CUI_ACCESS_RULES});
    List<String> failures = new ArrayList<>();
    for (String[] answer : answers) {
      for (String error :
          spec.validateContextResponse(answer[0], JsonParser.parseString(answer[1]))) {
        failures.add(answer[0] + " " + error);
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  void callerMutationCannotAlterLaterCachedCuiProcessResult() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                CUI_PREFIX,
                ClientStubServer.description(CUI_PREFIX),
                CUI_PREFIX + "/scheme/process",
                CUI_ENVELOPE),
            "20260720.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("us-gov-cui", "20260720.0.0");
        var first = context.scheme().process("CUI");
        assertEquals("CUI", first.result().assertions().get(0).conceptId());
        first.result().assertions().clear();
        var later = context.scheme().process("CUI");
        assertEquals("CUI", later.result().assertions().get(0).conceptId());
        assertEquals(2, stub.requestCount());
      }
    }
  }

  @Test
  void cachedCuiConceptDescriptionReturnsFreshParsedResult() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                CUI_PREFIX,
                ClientStubServer.description(CUI_PREFIX),
                CUI_PREFIX + "/scheme/describe-concept",
                CUI_CONCEPT_ENVELOPE),
            "20260720.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("us-gov-cui", "20260720.0.0");
        var first = context.scheme().describeConcept("CUI", "cui_designator");
        var later = context.scheme().describeConcept("CUI", "cui_designator");
        assertNotSame(first.result(), later.result());
        assertEquals("CUI", later.result().lines().get(0));
        assertEquals(2, stub.requestCount());
      }
    }
  }

  @Test
  void callerMutationCannotAlterLaterCachedAccessRules() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                CUI_PREFIX,
                ClientStubServer.description(CUI_PREFIX),
                CUI_PREFIX + "/access/rules",
                CUI_ACCESS_RULES),
            "20260720.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("us-gov-cui", "20260720.0.0");
        var first = context.access().rules();
        first.get(0).attributesNeeded().clear();
        var later = context.access().rules();
        assertEquals("agency", later.get(0).attributesNeeded().get(0));
        assertEquals(2, stub.requestCount());
      }
    }
  }

  @Test
  void malformedConceptAndAccessResponsesAreNotCached() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                CUI_PREFIX,
                ClientStubServer.description(CUI_PREFIX),
                CUI_PREFIX + "/scheme/describe-concept",
                "{\"resolution\":{},\"result\":{}}",
                CUI_PREFIX + "/access/rules",
                "{}"),
            "20260720.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("us-gov-cui", "20260720.0.0");
        assertThrows(
            EzClassifyClientException.class,
            () -> context.scheme().describeConcept("CUI", "cui_designator"));
        stub.setResponse(CUI_PREFIX + "/scheme/describe-concept", CUI_CONCEPT_ENVELOPE);
        assertEquals(
            "CUI", context.scheme().describeConcept("CUI", "cui_designator").result().conceptId());
        assertThrows(EzClassifyClientException.class, () -> context.access().rules());
        stub.setResponse(CUI_PREFIX + "/access/rules", CUI_ACCESS_RULES);
        assertEquals("CUI rule", context.access().rules().get(0).name());
        assertEquals(5, stub.requestCount());
      }
    }
  }

  @Test
  void repeatedProcessUsesOneWireRequestButAccessStillAuditsEachCall() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/process",
                PROCESS_ENVELOPE,
                PREFIX + "/access/check",
                DECISION),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        assertEquals(1, stub.requestCount());
        context.scheme().process("H");
        context.scheme().process("H");
        assertEquals(2, stub.requestCount());
        context.scheme().process("OTHER");
        assertEquals(3, stub.requestCount());
        context.access().checkByMarking("H", "{}");
        context.access().checkByMarking("H", "{}");
        assertEquals(5, stub.requestCount());
      }
    }
  }

  @Test
  void accessDecisionOptInRequiresConcreteVersionAndClearCacheRefetches() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX, ClientStubServer.description(PREFIX), PREFIX + "/access/check", DECISION),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        assertThrows(
            IllegalArgumentException.class,
            () -> client.context("cache-test", "latest", new ContextCacheOptions(true, false)));
        EzClassifyContext context =
            client.context("cache-test", "1.0.0", new ContextCacheOptions(true, false));
        context.access().checkByMarking("H", "{}");
        context.access().checkByMarking("H", "{}");
        assertEquals(2, stub.requestCount());
        context.clearCache();
        context.access().checkByMarking("H", "{}");
        assertEquals(3, stub.requestCount());
      }
    }
  }

  @Test
  void malformedSuccessIsRetriedAndCacheCanBeDisabled() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(PREFIX, ClientStubServer.description(PREFIX), PREFIX + "/scheme/process", "{}"),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        assertThrows(EzClassifyClientException.class, () -> context.scheme().process("H"));
        stub.setResponse(PREFIX + "/scheme/process", PROCESS_ENVELOPE);
        context.scheme().process("H");
        assertEquals(3, stub.requestCount());
        EzClassifyContext noCache =
            client.context("cache-test", "1.0.0", new ContextCacheOptions(false, true));
        noCache.scheme().process("H");
        noCache.scheme().process("H");
        assertEquals(6, stub.requestCount());
      }
    }
  }

  @Test
  void aResultMissingAVerdictIsRefusedAndNotCached() throws Exception {
    // The cached call decodes before it keeps anything, so a malformed answer is never served to
    // the next caller; the retry reaches the server and reads the corrected answer.
    String noInputWasValid = VALIDATE_ENVELOPE.replace("\"inputWasValid\":true,", "");
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/validate",
                noInputWasValid),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        EzClassifyClientException refused =
            assertThrows(EzClassifyClientException.class, () -> context.scheme().validate("H"));
        assertTrue(refused.getMessage().contains("inputWasValid"), refused.getMessage());
        stub.setResponse(PREFIX + "/scheme/validate", VALIDATE_ENVELOPE);
        assertTrue(context.scheme().validate("H").result().inputWasValid());
        assertEquals(3, stub.requestCount());
      }
    }
  }

  @Test
  void emptyOperationResultIsNotRetainedUnderConcretePin() throws Exception {
    String emptyResult =
        "{\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":{}}";
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/process",
                emptyResult),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        assertThrows(EzClassifyClientException.class, () -> context.scheme().process("H"));
        stub.setResponse(PREFIX + "/scheme/process", PROCESS_ENVELOPE);
        assertEquals("H", context.scheme().process("H").result().rendered());
        assertEquals(3, stub.requestCount());
      }
    }
  }

  @Test
  void latestBindingClearsOldAnswersWhenResolvedVersionChanges() throws Exception {
    String latest = "/v1/ctx/cache-test/latest";
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                latest,
                ClientStubServer.description(latest),
                latest + "/scheme/process",
                PROCESS_ENVELOPE),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "latest");
        context.scheme().process("H");
        context.scheme().process("H");
        assertEquals(2, stub.requestCount());

        stub.setResolvedVersionHeader("2.0.0");
        context.resolve();
        assertEquals("2.0.0", context.resolvedVersion());
        context.scheme().process("H");
        context.scheme().process("H");
        assertEquals(4, stub.requestCount());
      }
    }
  }

  @Test
  void aBindingWithoutAVersionIsUnpinnedRefusesDecisionCachingAndFollowsTheResolvedVersion()
      throws Exception {
    String describe = "/v1/ctx/cache-test/latest";
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                describe,
                ClientStubServer.description(describe),
                "/v1/ctx/cache-test/scheme/process",
                PROCESS_ENVELOPE),
            "1.0.0")) {
      try (EzClassifyClient client =
          new EzClassifyClient(stub.url(), new RestClientCredentials(null, "signed-context"))) {
        assertThrows(
            IllegalArgumentException.class,
            () -> client.context("cache-test", null, new ContextCacheOptions(true, false)));
        EzClassifyContext context = client.context("cache-test");
        context.scheme().process("H");
        context.scheme().process("H");
        assertEquals(2, stub.requestCount());

        stub.setResolvedVersionHeader("2.0.0");
        context.resolve();
        context.scheme().process("H");
        context.scheme().process("H");
        assertEquals(4, stub.requestCount());
      }
    }
  }

  @Test
  void objectMemberOrderDoesNotCreateAnotherCacheKey() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/process",
                PROCESS_ENVELOPE),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        JsonObject first = new JsonObject();
        first.addProperty("input", "H");
        first.addProperty("outputFormat", "banner");
        JsonObject reordered = new JsonObject();
        reordered.addProperty("outputFormat", "banner");
        reordered.addProperty("input", "H");
        context.postResult(
            "scheme/process", first, value -> WireJson.fromTree(value, ProcessResult.class));
        context.postResult(
            "scheme/process", reordered, value -> WireJson.fromTree(value, ProcessResult.class));
        assertEquals(2, stub.requestCount());
      }
    }
  }

  @Test
  void authenticationFailureDiscardsCachedAnswers() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/process",
                PROCESS_ENVELOPE,
                PREFIX + "/access/check",
                DECISION),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        context.scheme().process("H");
        stub.setStatus(PREFIX + "/access/check", 401);
        assertThrows(
            EzClassifyClientException.class, () -> context.access().checkByMarking("H", "{}"));
        context.scheme().process("H");
        assertEquals(4, stub.requestCount());
      }
    }
  }

  @Test
  void explainAndProcessKeepSeparateEntriesForTheSameBody() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/process",
                PROCESS_ENVELOPE,
                PREFIX + "/scheme/explain",
                TRACE_ENVELOPE),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        context.scheme().process("H");
        assertEquals("H", context.scheme().explain("H").result().rendered());
        assertEquals(3, stub.requestCount());
        context.scheme().process("H");
        context.scheme().explain("H");
        assertEquals(3, stub.requestCount());
      }
    }
  }

  @Test
  void everyRequestFieldThatChangesTheAnswerIsPartOfTheKey() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/process",
                PROCESS_ENVELOPE,
                PREFIX + "/scheme/validate",
                VALIDATE_ENVELOPE,
                PREFIX + "/scheme/explain",
                TRACE_ENVELOPE),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        ContextScheme scheme = context.scheme();
        ProcessingContext part = new ProcessingContext(ProcessingRole.PART, null);
        int before = stub.requestCount();

        // Each call differs from the bare one by one field, so each is its own entry.
        scheme.process("H");
        scheme.process("H", ProcessOptions.builder().outputs(List.of("marking/short")).build());
        scheme.process("H", ProcessOptions.builder().outputs(List.of("marking/long")).build());
        scheme.process("H", ProcessOptions.builder().suggestFor(List.of("level")).build());
        scheme.process("H", ProcessOptions.builder().assertions(List.of(LEVEL)).build());
        scheme.process("H", ProcessOptions.builder().context(part).build());
        scheme.process("H", ProcessOptions.builder().parts(List.of("(H)")).build());
        scheme.process("H", ProcessOptions.builder().inputFormat("short").build());
        scheme.process("H", ProcessOptions.builder().outputFormat("marking/short").build());
        scheme.process("H", ProcessOptions.builder().surface("portion").build());
        assertEquals(before + 10, stub.requestCount());

        scheme.validate("H");
        scheme.validate(List.of(LEVEL));
        scheme.validate("H", ValidateOptions.builder().context(part).build());
        scheme.explain("H", ExplainOptions.builder().assertions(List.of(LEVEL)).build());
        assertEquals(before + 14, stub.requestCount());

        // Asking again for any of them is answered from the cache.
        scheme.process("H", ProcessOptions.builder().outputs(List.of("marking/short")).build());
        scheme.process("H", ProcessOptions.builder().suggestFor(List.of("level")).build());
        scheme.validate(List.of(LEVEL));
        scheme.validate("H", ValidateOptions.builder().context(part).build());
        scheme.explain("H", ExplainOptions.builder().assertions(List.of(LEVEL)).build());
        assertEquals(before + 14, stub.requestCount());
      }
    }
  }

  @Test
  void suggestForCategoriesIsCachedAndAPartialAnswerIsNot() throws Exception {
    String partial =
        "{\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":{}}";
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/suggest-for-categories",
                partial),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        ContextScheme scheme = context.scheme();
        assertThrows(
            EzClassifyClientException.class,
            () -> scheme.suggestForCategories(List.of(LEVEL), List.of("level")));
        stub.setResponse(PREFIX + "/scheme/suggest-for-categories", SUGGESTIONS_ENVELOPE);
        scheme.suggestForCategories(List.of(LEVEL), List.of("level"));
        scheme.suggestForCategories(List.of(LEVEL), List.of("level"));
        assertEquals(3, stub.requestCount());
      }
    }
  }

  @Test
  void suggestForCategoriesNamingNoCategoryAcceptsTheEmptyMap() throws Exception {
    // The answer is the map itself, so naming no category legitimately comes back as {}. Every
    // other operation still refuses an empty result as truncated.
    String empty =
        "{\"resolution\":{\"contextId\":\"cache-test\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":{}}";
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(
                PREFIX,
                ClientStubServer.description(PREFIX),
                PREFIX + "/scheme/suggest-for-categories",
                empty,
                PREFIX + "/scheme/process",
                empty),
            "1.0.0")) {
      try (EzClassifyClient client = stub.newClient()) {
        EzClassifyContext context = client.context("cache-test", "1.0.0");
        assertEquals(
            Map.of(), context.scheme().suggestForCategories(List.of(LEVEL), List.of()).result());
        assertThrows(EzClassifyClientException.class, () -> context.scheme().process("H"));
      }
    }
  }
}
