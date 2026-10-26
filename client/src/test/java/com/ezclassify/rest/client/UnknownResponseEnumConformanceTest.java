package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.AssertionConfidence;
import com.ezclassify.rest.contract.ContextCrosswalkTranslationResult;
import com.ezclassify.rest.contract.ContextDocumentAuditResult;
import com.ezclassify.rest.contract.ContextResolution;
import com.ezclassify.rest.contract.ContextSchemeDiffResult;
import com.ezclassify.rest.contract.ContextSchemeProcessResult;
import com.ezclassify.rest.contract.ContextSchemeScanResult;
import com.ezclassify.rest.contract.CrosswalkOutcome;
import com.ezclassify.rest.contract.DocumentResponseProfile;
import com.ezclassify.rest.contract.ExpandableStringEnum;
import com.ezclassify.rest.contract.GuideDeterminationInput;
import com.ezclassify.rest.contract.GuideFindingDecision;
import com.ezclassify.rest.contract.GuideFindingDetermination;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.ScanLimits;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Responses are open for enum values too: a value a newer server adds decodes instead of failing,
 * and keeps its wire token.
 *
 * <p>The contract is open-response and closed-request. Every contract enum is an expandable string
 * enum, so a response token this build does not know decodes to an instance whose {@code value()}
 * is {@code UNKNOWN} and whose {@code toWire()} is the token the server sent. The cases cover each
 * kind of place an enum sits: a field of the result, elements of a list of models (through both the
 * typed decoder and the handwritten tree readers), a model nested in the result, a nested record
 * enum, and the source-reference discriminator in every envelope's resolution.
 *
 * <p>The closed side belongs to the server. A value read from a response goes back unchanged in the
 * next request, which is how the banner rollup feeds processed portions into aggregate; only the
 * strict {@code fromWire} refuses an undeclared token a caller types in.
 */
class UnknownResponseEnumConformanceTest {

  private static final String CTX = "/v1/ctx/acme-ctx/1.0.0";
  private static final String RESOLUTION =
      "{\"contextId\":\"acme-ctx\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]}";

  private static final String NOTHING_EMITTED =
      "{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]}";

  private static ClientStubServer stub;

  private static String envelope(String resolution, String result) {
    return "{\"resolution\":"
        + resolution
        + ",\"operationLimitations\":[],\"result\":"
        + result
        + "}";
  }

  @BeforeAll
  static void start() throws IOException {
    Map<String, String> routes = new HashMap<>();
    routes.put(CTX, ClientStubServer.description(CTX));
    routes.put(
        CTX + "/document/audit",
        envelope(
            RESOLUTION,
            "{\"markingRequirements\":{\"whole\":true,\"part\":true},"
                + "\"whole\":null,\"wholes\":[],\"wholeValidation\":null,\"parts\":[],"
                + "\"wholeCoversParts\":null,\"unmarkedSpans\":[],\"proceduralFlags\":[],"
                + "\"overallVerdict\":\"needs-review\",\"vocabulary\":null,"
                + "\"documentDigest\":\"sha256:doc\",\"guideAssessment\":null,\"limitations\":[]}"));
    routes.put(
        CTX + "/scheme/process",
        envelope(
            RESOLUTION,
            "{\"valid\":false,\"rendered\":\"H\",\"inputWasValid\":true,\"notices\":[],"
                + "\"appliedFixes\":[],\"proposedFixes\":[],\"renders\":{},"
                + "\"assessment\":"
                + NOTHING_EMITTED
                + ","
                + "\"assertions\":[{\"conceptId\":\"L_H\",\"categoryId\":\"level\","
                + "\"confidence\":\"probable\",\"source\":\"parse\"}],"
                + "\"errors\":[{\"constraintId\":\"c1\",\"message\":\"m\",\"severity\":\"fatal\"}],"
                + "\"warnings\":[{\"kind\":\"advisory\",\"message\":\"w\"}]}"));
    routes.put(
        CTX + "/scheme/aggregate",
        envelope(
            RESOLUTION,
            "{\"valid\":true,\"rendered\":\"H\",\"assertions\":[],\"errors\":[],\"warnings\":[],"
                + "\"appliedFixes\":[],\"proposedFixes\":[],\"notices\":[]}"));
    routes.put(
        CTX + "/scheme/diff",
        envelope(
            RESOLUTION,
            "{\"added\":[{\"conceptId\":\"L_H\",\"categoryId\":\"level\","
                + "\"confidence\":\"probable\",\"source\":\"parse\"}],\"removed\":[],\"common\":[]}"));
    routes.put(
        CTX + "/scheme/scan",
        envelope(
            RESOLUTION,
            "{\"results\":[],\"limits\":{\"status\":\"paused\",\"limitHit\":\"memory\","
                + "\"regionsFound\":0}}"));
    routes.put(
        CTX + "/crosswalk/translate",
        envelope(
            "{\"contextId\":\"acme-ctx\",\"resolvedVersion\":\"1.0.0\","
                + "\"artifacts\":[{\"kind\":\"policy\",\"id\":\"p\",\"version\":\"1.0.0\"}],"
                + "\"sources\":[{\"providerId\":\"idp\",\"reproducibility\":\"pinned\","
                + "\"snapshotId\":\"snap-1\",\"attribution\":{\"name\":\"IdP\",\"license\":\"internal\"}}]}",
            "{\"crosswalk\":{\"kind\":\"crosswalk\",\"id\":\"x\",\"version\":\"1.0.0\"},"
                + "\"sourceScheme\":{\"kind\":\"scheme\",\"id\":\"a\",\"version\":\"1.0.0\"},"
                + "\"targetScheme\":{\"kind\":\"scheme\",\"id\":\"b\",\"version\":\"1.0.0\"},"
                + "\"source\":{\"valid\":true,\"inputWasValid\":true,\"rendered\":\"H\","
                + "\"assertions\":[],\"errors\":[],\"appliedFixes\":[],"
                + "\"assessment\":"
                + NOTHING_EMITTED
                + ","
                + "\"warnings\":[],\"notices\":[]},\"selections\":[],\"status\":\"complete\","
                + "\"outcomes\":["
                + "{\"source\":{\"conceptId\":\"H\",\"categoryId\":\"level\"},\"mappingId\":\"m1\","
                + "\"disposition\":\"carry\",\"targets\":[],\"reason\":\"r\"},"
                + "{\"source\":{\"conceptId\":\"H\",\"categoryId\":\"level\"},\"mappingId\":\"m2\","
                + "\"disposition\":\"escalate\",\"targets\":[],\"reason\":\"r\"}],"
                + "\"target\":{\"valid\":true,\"inputWasValid\":true,\"rendered\":\"D-H\","
                + "\"assertions\":[],\"errors\":[],\"appliedFixes\":[],"
                + "\"assessment\":"
                + NOTHING_EMITTED
                + ","
                + "\"warnings\":[],\"notices\":[]}}"));
    stub = new ClientStubServer(routes, "1.0.0");
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  private static void assertUnknown(String token, ExpandableStringEnum<?> decoded) {
    assertEquals(token, decoded.toWire());
    assertFalse(decoded.isKnown(), token);
    assertEquals("UNKNOWN", decoded.value().name());
  }

  private static EzClassifyContext connect() {
    return EzClassifyClient.connect(stub.url(), "acme-ctx", "1.0.0");
  }

  @Test
  void anUnknownValueInATopLevelFieldKeepsItsToken() {
    ContextDocumentAuditResult audit =
        assertDoesNotThrow(() -> connect().document().audit("d"), "unknown verdict must decode");

    assertUnknown("needs-review", audit.result().overallVerdict());
    assertEquals("sha256:doc", audit.result().documentDigest());
  }

  @Test
  void unknownValuesInListElementsKeepTheirTokens() {
    ContextSchemeProcessResult processed =
        assertDoesNotThrow(() -> connect().scheme().process("H"), "unknown values must decode");

    ProcessResult result = processed.result();
    assertUnknown("probable", result.assertions().get(0).confidence());
    assertEquals("L_H", result.assertions().get(0).conceptId());
    assertUnknown("fatal", result.errors().get(0).severity());
    assertUnknown("advisory", result.warnings().get(0).kind());
  }

  @Test
  void anUnknownValueReadByTheHandwrittenTreeDecoderKeepsItsToken() {
    ContextSchemeDiffResult diff =
        assertDoesNotThrow(() -> connect().scheme().diff("H", "L"), "unknown value must decode");

    assertUnknown("probable", diff.result().added().get(0).confidence());
  }

  @Test
  void unknownValuesInANestedModelKeepTheirTokens() {
    ContextSchemeScanResult scanned =
        assertDoesNotThrow(() -> connect().scheme().scan("t"), "unknown limits must decode");

    ScanLimits limits = scanned.result().limits();
    assertUnknown("paused", limits.status());
    assertUnknown("memory", limits.limitHit());
    assertEquals(0, limits.regionsFound());
  }

  @Test
  void anUnknownDiscriminatorAndNestedRecordEnumKeepTheirTokens() {
    ContextCrosswalkTranslationResult translated =
        assertDoesNotThrow(
            () -> connect().crosswalk().translate("x", "H"), "unknown values must decode");

    ContextResolution resolution = translated.resolution();
    assertUnknown("policy", resolution.artifacts().get(0).kind());
    assertUnknown("pinned", resolution.sources().get(0).reproducibility());
    assertEquals("snap-1", resolution.sources().get(0).snapshotId());
    assertSame(
        CrosswalkOutcome.Disposition.CARRY, translated.result().outcomes().get(0).disposition());
    assertUnknown("escalate", translated.result().outcomes().get(1).disposition());
  }

  @Test
  void aRequestCarryingAnUnknownEnumValueIsSentUnchanged() {
    // The server, not the client, decides whether a request may carry a value. A value the SDK
    // does not know reaches it byte for byte.
    EzClassifyContext ctx = connect();
    GuideDeterminationInput determinations =
        new GuideDeterminationInput(
            List.of(
                new GuideFindingDetermination(
                    "f1", "sha256:doc", GuideFindingDecision.fromString("deferred"), "r", null)),
            List.of());

    ctx.document().audit("d", determinations, DocumentResponseProfile.fromString("everything"));

    JsonObject sent = JsonParser.parseString(stub.lastRequest().body()).getAsJsonObject();
    assertEquals("everything", sent.get("responseProfile").getAsString());
    assertEquals(
        "deferred",
        sent.getAsJsonObject("guideDeterminations")
            .getAsJsonArray("findings")
            .get(0)
            .getAsJsonObject()
            .get("decision")
            .getAsString());
  }

  @Test
  void theStrictDecoderRefusesAnUndeclaredTokenACallerTypesIn() {
    assertThrows(
        IllegalArgumentException.class, () -> DocumentResponseProfile.fromWire("everything"));
    assertThrows(IllegalArgumentException.class, () -> AssertionConfidence.fromWire("probable"));
    assertSame(AssertionConfidence.INFERRED, AssertionConfidence.fromWire("inferred"));
  }

  @Test
  void aRequestWithKnownEnumValuesIsSent() {
    // Negative control for the refusal above: the same request with declared values goes out.
    EzClassifyContext ctx = connect();
    GuideDeterminationInput determinations =
        new GuideDeterminationInput(
            List.of(
                new GuideFindingDetermination(
                    "f1", "sha256:doc", GuideFindingDecision.ACCEPTED, "r", null)),
            List.of());

    ctx.document().audit("d", determinations, DocumentResponseProfile.SUMMARY);

    String body = stub.lastRequest().body();
    assertEquals(true, body.contains("\"decision\":\"accepted\""), body);
    assertEquals(true, body.contains("\"responseProfile\":\"summary\""), body);
  }

  @Test
  void aDecodedUnknownConfidenceRollsUpThroughAggregate() {
    // The banner rollup path: portions come back from process and go straight into aggregate.
    EzClassifyContext ctx = connect();
    Assertion portion = ctx.scheme().process("S").result().assertions().get(0);
    int before = stub.requestCount();

    ctx.scheme().aggregateAssertions(List.of(List.of(portion), List.of(portion)));

    assertEquals(before + 1, stub.requestCount(), "the aggregate request must be sent");
    JsonObject sent = JsonParser.parseString(stub.lastRequest().body()).getAsJsonObject();
    JsonArray sets = sent.getAsJsonArray("assertionSets");
    assertEquals(2, sets.size());
    for (int i = 0; i < sets.size(); i++) {
      JsonObject echoed = sets.get(i).getAsJsonArray().get(0).getAsJsonObject();
      assertEquals("probable", echoed.get("confidence").getAsString());
    }
  }

  @Test
  void aProcessedAssertionTravelsBackToAccessCheckUnchanged() throws IOException {
    // Every field the server sent goes back, byte for byte: dropping expiresAt would strip an
    // expiry from a data assertion on access check. The fixture is in record component order.
    String assertion =
        "{\"categoryId\":\"level\",\"conceptId\":\"L_H\",\"confidence\":\"certain\","
            + "\"expiresAt\":\"2030-01-01T00:00:00Z\",\"metadata\":{\"k\":\"v\",\"n\":3},"
            + "\"provenance\":{\"reproducibility\":\"reproducible\",\"snapshotId\":\"snap-1\","
            + "\"providerId\":\"idp\",\"attribution\":{\"name\":\"IdP\",\"license\":\"internal\"}},"
            + "\"replacedConceptId\":\"L_OLD\",\"source\":\"parse\",\"sourceRule\":\"r1\","
            + "\"value\":\"v1\"}";
    Map<String, String> routes = new HashMap<>();
    routes.put(CTX, ClientStubServer.description(CTX));
    routes.put(
        CTX + "/scheme/process",
        envelope(
            RESOLUTION,
            "{\"valid\":true,\"rendered\":\"H\",\"inputWasValid\":true,\"notices\":[],"
                + "\"appliedFixes\":[],\"proposedFixes\":[],\"renders\":{},"
                + "\"assessment\":"
                + NOTHING_EMITTED
                + ",\"assertions\":["
                + assertion
                + "],\"errors\":[],\"warnings\":[]}"));
    routes.put(CTX + "/access/check", envelope(RESOLUTION, "{\"granted\":true,\"rules\":[]}"));
    try (ClientStubServer local = new ClientStubServer(routes, "1.0.0")) {
      EzClassifyContext ctx = EzClassifyClient.connect(local.url(), "acme-ctx", "1.0.0");
      List<Assertion> data = ctx.scheme().process("H").result().assertions();

      ctx.access().check(data, Map.of("clearance", "H"));

      JsonObject sent = JsonParser.parseString(local.lastRequest().body()).getAsJsonObject();
      assertEquals(assertion, sent.getAsJsonArray("assertions").get(0).toString());
    }
  }
}
