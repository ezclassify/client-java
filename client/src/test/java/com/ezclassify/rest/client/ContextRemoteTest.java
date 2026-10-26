package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ezclassify.rest.contract.AccessDecision;
import com.ezclassify.rest.contract.AccessRequirementsDescription;
import com.ezclassify.rest.contract.AccessRuleDescription;
import com.ezclassify.rest.contract.AggregateResult;
import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.AssertionConfidence;
import com.ezclassify.rest.contract.CheckCoverageResult;
import com.ezclassify.rest.contract.ConceptDescription;
import com.ezclassify.rest.contract.ConceptNoticeType;
import com.ezclassify.rest.contract.ConceptView;
import com.ezclassify.rest.contract.ContextCrosswalkTranslationResult;
import com.ezclassify.rest.contract.ContextDocumentAccessAssessmentResult;
import com.ezclassify.rest.contract.ContextDocumentAuditResult;
import com.ezclassify.rest.contract.DiffResult;
import com.ezclassify.rest.contract.DocumentAuditReport;
import com.ezclassify.rest.contract.DocumentOverallVerdict;
import com.ezclassify.rest.contract.DocumentResponseProfile;
import com.ezclassify.rest.contract.EvaluationResult;
import com.ezclassify.rest.contract.ExplanationTrace;
import com.ezclassify.rest.contract.FilterResult;
import com.ezclassify.rest.contract.FixAction;
import com.ezclassify.rest.contract.GuideBreakdownEntry;
import com.ezclassify.rest.contract.GuideScanResult;
import com.ezclassify.rest.contract.GuideViewResponse;
import com.ezclassify.rest.contract.Notice;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.ProcessingContext;
import com.ezclassify.rest.contract.ProcessingRole;
import com.ezclassify.rest.contract.RecognitionCorrectionReason;
import com.ezclassify.rest.contract.RecognitionStatus;
import com.ezclassify.rest.contract.RenderPreservation;
import com.ezclassify.rest.contract.RuleDescription;
import com.ezclassify.rest.contract.ScanResponseBody;
import com.ezclassify.rest.contract.ScanResult;
import com.ezclassify.rest.contract.ScanStatus;
import com.ezclassify.rest.contract.SchemeViewResponse;
import com.ezclassify.rest.contract.SuggestResult;
import com.ezclassify.rest.contract.Term;
import com.ezclassify.rest.contract.ValidateResult;
import com.ezclassify.rest.contract.ValidationError;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the context-bound client surface: {@link EzClassifyClient#connect} and the {@code
 * ctx.scheme()/guide()/access()/document()} sub-surfaces.
 *
 * <p>A JDK {@code com.sun.net.httpserver} stub records every request and returns canned JSON keyed
 * by the op-path suffix, always stamping the {@code X-EzClassify-Resolved-Version} header so the
 * wrapper can surface it.
 */
@SuppressWarnings("restriction")
class ContextRemoteTest {

  private static HttpServer mockServer;
  private static java.util.concurrent.ExecutorService mockExecutor;
  private static String baseUrl;

  // Recorded last request, for path/body assertions.
  static volatile String lastMethod;
  static volatile String lastPath;
  static volatile String lastRawPath;
  static volatile String lastQuery;
  static volatile String lastBody;
  static volatile String lastAuthorization;
  static volatile String lastContextToken;

  private static final String ASSESSMENT_OF_H =
      "\"assessment\":{\"emitted\":[{\"conceptId\":\"L_H\",\"categoryId\":\"level\",\"value\":\"A&B\"}],"
          + "\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]}";

  private static final String ASSESSMENT_OF_NOTHING =
      "\"assessment\":{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]}";

  /** A complete suggestion for the HIGH level, as suggest and process both answer it. */
  private static final String SUGGEST_HIGH =
      "{\"conceptId\":\"L_H\",\"categoryId\":\"level\",\"label\":\"H\",\"description\":\"The high level\","
          + "\"name\":\"High\",\"views\":{\"default\":\"H\"},\"group\":\"Level\",\"selected\":%s,\"order\":0}";

  private static final Map<String, String> ROUTES = new HashMap<>();

  static {
    ROUTES.put(
        "/scheme/process",
        "{\"assertions\":[{\"conceptId\":\"L_H\",\"categoryId\":\"level\",\"confidence\":\"certain\",\"source\":\"parse\",\"value\":\"A&B\"}],"
            + "\"valid\":true,\"inputWasValid\":false,\"errors\":[],\"warnings\":[],"
            + "\"appliedFixes\":[{\"ruleId\":\"_recognition_case\",\"action\":\"reorder\",\"message\":\"Read `h` as `H` (case).\"}],"
            + "\"rendered\":\"H\","
            + ASSESSMENT_OF_H
            + ",\"recognition\":{\"status\":\"complete\",\"inputFormat\":\"marking/long\",\"role\":\"whole\",\"surface\":\"marking\",\"unmatched\":[],"
            + "\"corrections\":[{\"start\":0,\"end\":1,\"original\":\"h\",\"replacement\":\"H\",\"reason\":\"case\"}]},"
            + "\"notices\":[{\"conceptId\":\"ORG\",\"conceptName\":\"Organization Only\",\"type\":\"handling\",\"text\":\"controlled\",\"required\":true}],"
            + "\"proposedFixes\":[],"
            + "\"renders\":{\"marking/long\":{\"rendered\":\"H\","
            + ASSESSMENT_OF_H
            + "},\"marking/short\":{\"rendered\":\"(H)\",\"assessment\":{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]}}},"
            + "\"suggestionsFor\":{\"level\":{\"suggestions\":["
            + SUGGEST_HIGH.formatted("true")
            + "],\"warnings\":[]}}}");
    ROUTES.put(
        "/scheme/apply-preset",
        "{\"valid\":true,\"rendered\":\"H\",\"inputWasValid\":true,\"assertions\":[],\"errors\":[],\"warnings\":[],\"appliedFixes\":[],"
            + ASSESSMENT_OF_NOTHING
            + ",\"notices\":[]}");
    ROUTES.put(
        "/scheme/validate",
        "{\"valid\":false,\"rendered\":\"H\",\"inputWasValid\":false,\"assertions\":[],\"warnings\":[],\"appliedFixes\":[],"
            + ASSESSMENT_OF_NOTHING
            + ",\"recognition\":{\"status\":\"partial\",\"inputFormat\":\"marking/short\",\"role\":\"part\",\"surface\":\"marking\",\"unmatched\":[{\"start\":2,\"end\":6,\"text\":\"HGIH\"}]},"
            + "\"errors\":[{\"constraintId\":\"_unmatched_token\",\"message\":\"bad\",\"didYouMean\":[\"L_H\"]}],\"notices\":[]}");
    ROUTES.put(
        "/scheme/explain",
        "{\"input\":\"HIGH\",\"schemeId\":\"s\",\"entries\":[{\"stage\":\"infer\",\"action\":\"add\",\"detail\":\"Added STAFF: HIGH requires it.\",\"conceptId\":\"STAFF\",\"ruleId\":\"high-needs-staff\",\"triggerConceptIds\":[\"L_H\"]}],"
            + "\"repairs\":[],\"validationErrors\":[],\"inferences\":[{\"ruleId\":\"high-needs-staff\",\"conceptId\":\"STAFF\",\"explanation\":\"HIGH requires STAFF.\"}],"
            + "\"finalAssertions\":[{\"categoryId\":\"level\",\"conceptId\":\"L_H\",\"confidence\":\"certain\",\"source\":\"parse\"}],\"rendered\":\"HIGH//STAFF\"}");
    ROUTES.put(
        "/scheme/suggest",
        "{\"suggestions\":["
            + SUGGEST_HIGH.formatted("false")
            + "],\"warnings\":[{\"kind\":\"deprecation\",\"message\":\"retired\",\"conceptId\":\"L_X\"}]}");
    ROUTES.put(
        "/scheme/suggest-for-categories",
        "{\"level\":{\"suggestions\":["
            + SUGGEST_HIGH.formatted("true")
            + "],\"warnings\":[]},\"audience\":{\"suggestions\":[],\"warnings\":[]}}");
    ROUTES.put(
        "/scheme/aggregate",
        "{\"valid\":true,\"rendered\":\"H\",\"assertions\":[],\"errors\":[],\"warnings\":[],"
            + "\"appliedFixes\":[],\"proposedFixes\":[],"
            + "\"notices\":[{\"conceptId\":\"ORG\",\"conceptName\":\"Organization Only\",\"type\":\"handling\",\"text\":\"controlled\",\"required\":true}]}");
    ROUTES.put(
        "/scheme/scan",
        "{\"results\":[{\"marking\":\"H\",\"start\":5,\"end\":6,\"type\":\"marking/short\",\"wrapped\":true,\"role\":\"part\",\"result\":{\"valid\":true,\"rendered\":\"H\",\"inputWasValid\":true,\"assertions\":[],\"errors\":[],\"warnings\":[],\"appliedFixes\":[],"
            + ASSESSMENT_OF_NOTHING
            + ",\"recognition\":{\"status\":\"complete\",\"inputFormat\":\"marking/short\",\"role\":\"part\",\"surface\":\"marking\",\"unmatched\":[]},\"notices\":[]}}],\"limits\":{\"status\":\"complete\",\"regionsFound\":1}}");
    ROUTES.put(
        "/scheme/render",
        "{\"result\":\"LEVEL-HIGH\",\"assessment\":{\"emitted\":[{\"conceptId\":\"L_H\",\"categoryId\":\"level\",\"value\":\"A&B\"}],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]}}");
    ROUTES.put("/scheme/normalize", "{\"result\":\"LEVEL-HIGH\"}");
    ROUTES.put(
        "/scheme/check-coverage",
        "{\"covered\":false,\"missing\":[],\"missingLabels\":[\"TS\"],\"excess\":[],\"excessLabels\":[],\"rendered\":\"TS\"}");
    ROUTES.put(
        "/scheme/describe-concept",
        "{\"conceptId\":\"L_H\",\"categoryId\":\"level\",\"name\":\"High\",\"lines\":[\"High\"],\"definition\":\"the high level\"}");
    ROUTES.put(
        "/scheme/diff",
        "{\"added\":[{\"conceptId\":\"A\",\"categoryId\":\"c\",\"confidence\":\"certain\",\"source\":\"parse\"}],\"removed\":[],\"common\":[]}");
    ROUTES.put(
        "/scheme/filter-concepts",
        "{\"visibleIds\":[\"a\",\"b\"],\"categoryCounts\":{\"level\":{\"visible\":2,\"total\":5}},\"total\":{\"visible\":2,\"total\":9}}");
    ROUTES.put(
        "/scheme/notices",
        "[{\"conceptId\":\"ORG\",\"conceptName\":\"Organization Only\",\"type\":\"handling\",\"text\":\"controlled\",\"required\":true}]");
    ROUTES.put(
        "/scheme/view",
        "{\"id\":\"s\",\"name\":\"Scheme\",\"description\":\"d\",\"categories\":[{\"id\":\"level\",\"label\":\"Level\",\"items\":[{\"id\":\"H\",\"name\":\"High\","
            + "\"views\":{\"default\":\"H\"},\"aliases\":[],\"categories\":[\"level\"],\"parents\":[],\"position\":2}]}],"
            + "\"uncategorized\":[]}");
    ROUTES.put(
        "/scheme/concepts",
        "[{\"id\":\"L_H\",\"name\":\"High\",\"views\":{\"default\":\"H\"},\"categories\":[\"level\"],\"parents\":[],\"position\":2,\"aliases\":[],\"citation\":\"Source register, row 42\"},"
            + "{\"id\":\"L_L\",\"name\":\"Low\",\"views\":{\"default\":\"L\"},\"categories\":[\"level\"],\"parents\":[],\"position\":1,\"aliases\":[]}]");
    ROUTES.put(
        "/guide/scan",
        "[{\"rule\":{\"id\":\"r1\",\"name\":\"Rule 1\",\"outcome\":{\"rendered\":\"H\"}},\"matchedKeywords\":[\"secret\"],\"spans\":[{\"keyword\":\"secret\",\"start\":0,\"end\":6}],\"guideId\":\"g1\",\"provenance\":{\"providerId\":\"broker:guide-rule:srcref:x\",\"attribution\":{\"name\":\"Guide source\",\"license\":\"internal\"},\"reproducibility\":\"reproducible\",\"snapshotId\":\"snap-9\"}}]");
    ROUTES.put(
        "/guide/evaluate",
        "{\"aggregatedMarking\":\"H\",\"selectedRules\":[{\"id\":\"r1\",\"name\":\"Rule 1\",\"outcome\":{\"rendered\":\"H\"}}],\"activeComputedRules\":[],\"effectiveMarkings\":[\"H\"],\"guideId\":\"g1\","
            + "\"breakdown\":[{\"rule\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\",\"digest\":\"sha256:g\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\"},\"outcome\":{\"rendered\":\"H\"},"
            + "\"isComputed\":false,\"wasOverridden\":true,\"guideId\":\"g1\",\"overriddenBy\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\",\"digest\":\"sha256:g\"},\"ruleId\":\"r2\",\"ruleDigest\":\"sha256:r2\"}}],"
            + "\"contributingBreakdown\":[{\"rule\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\",\"digest\":\"sha256:g\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\"},\"outcome\":{\"rendered\":\"H\"},"
            + "\"isComputed\":true,\"wasOverridden\":false}]}");
    ROUTES.put(
        "/guide/filter-rules",
        "{\"visibleIds\":[\"r1\"],\"categoryCounts\":{},\"total\":{\"visible\":1,\"total\":3}}");
    ROUTES.put(
        "/guide/describe-rule",
        "{\"rule\":{\"artifact\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\",\"digest\":\"sha256:g1\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\",\"source\":{\"providerId\":\"guide-fixture-provider\",\"reproducibility\":\"reproducible\",\"snapshotId\":\"snapshot-r1\"}},\"guide\":{\"kind\":\"guide\",\"id\":\"g1\",\"version\":\"1.0.0\",\"digest\":\"sha256:g1\"},\"name\":\"Rule\",\"description\":\"Qualified guide rule\",\"outcomeText\":\"marks H\",\"whenText\":\"r2 is active\",\"isComputed\":false,\"dependsOn\":[{\"artifact\":{\"kind\":\"guide\",\"id\":\"g2\",\"version\":\"1.0.0\",\"digest\":\"sha256:g2\"},\"ruleId\":\"r2\",\"ruleDigest\":\"sha256:r2\",\"source\":{\"providerId\":\"guide-fixture-provider\",\"reproducibility\":\"reproducible\",\"snapshotId\":\"snapshot-r2\"}}],\"overrides\":[{\"artifact\":{\"kind\":\"guide\",\"id\":\"g3\",\"version\":\"1.0.0\",\"digest\":\"sha256:g3\"},\"ruleId\":\"r3\",\"ruleDigest\":\"sha256:r3\",\"source\":{\"providerId\":\"guide-fixture-provider\",\"reproducibility\":\"reproducible\",\"snapshotId\":\"snapshot-r3\"}}],\"lines\":[\"Rule\",\"marks H\"]}");
    ROUTES.put(
        "/guide/view",
        "{\"id\":\"g1\",\"name\":\"Guide\",\"description\":\"\",\"categories\":[],\"uncategorized\":[{\"id\":\"r1\","
            + "\"name\":\"Rule 1\",\"outcomeText\":\"marks H\",\"isComputed\":false,\"keywords\":[],\"matcherLabels\":[],"
            + "\"dependsOn\":[],\"dependsOnNames\":[],\"overrides\":[],\"overrideNames\":[]}]}");
    ROUTES.put(
        "/access/check",
        "{\"granted\":true,\"provenance\":{\"providerId\":\"broker:access-rule:srcref:x\",\"attribution\":{\"name\":\"Policy source\",\"license\":\"internal\",\"licenseUrl\":null,\"citation\":\"Policy source snapshot\"},\"reproducibility\":\"reproducible\",\"snapshotId\":\"snap-7\",\"locator\":\"memory://access\"},\"rules\":[{\"activation\":{\"matched\":true,\"assertions\":[],\"contextPaths\":[]},\"comparisons\":[{\"left\":{\"source\":\"subject\",\"path\":\"clearance\",\"categoryId\":null,\"assertions\":[],\"value\":\"HIGH\"},\"operator\":\"equals\",\"right\":{\"source\":\"literal\",\"path\":null,\"categoryId\":null,\"assertions\":[],\"value\":\"HIGH\"},\"matched\":true,\"missing\":[]}],\"granted\":true,\"name\":\"Clr\",\"reason\":\"clearance matched\",\"rule\":{\"artifact\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\",\"source\":{\"providerId\":\"broker:access-rule:srcref:x\",\"reproducibility\":\"reproducible\",\"snapshotId\":\"snap-7\"}},\"ruleIndex\":0,\"skipped\":false}]}");
    ROUTES.put(
        "/access/describe-requirements",
        "{\"marking\":\"RED//ORG\",\"assertions\":[],\"assertionDisplays\":[],\"access\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"scheme\":{\"kind\":\"scheme\",\"id\":\"s\",\"version\":\"1.0.0\",\"digest\":\"sha256:scheme\"},\"appliedRules\":[{\"artifact\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\",\"source\":null}],\"rules\":[],\"combinedRequirementText\":\"Org members only\",\"combinedAttributesNeeded\":[\"membership\"],\"lines\":[]}");
    ROUTES.put(
        "/access/describe-rule",
        "{\"rule\":{\"artifact\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\",\"source\":null},\"access\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"scheme\":{\"kind\":\"scheme\",\"id\":\"s\",\"version\":\"1.0.0\",\"digest\":\"sha256:scheme\"},\"name\":\"ORG\",\"description\":\"Organization membership\",\"requirementText\":\"Org members only\",\"whenText\":\"ORG applies\",\"attributesNeeded\":[\"membership\"],\"dependsOn\":[],\"overrides\":[],\"lines\":[]}");
    ROUTES.put(
        "/access/rules",
        "{\"rules\":[{\"rule\":{\"artifact\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"ruleId\":\"r1\",\"ruleDigest\":\"sha256:r1\"},\"access\":{\"kind\":\"access\",\"id\":\"ap\",\"version\":\"1.0.0\",\"digest\":\"sha256:access\"},\"scheme\":{\"kind\":\"scheme\",\"id\":\"s\",\"version\":\"1.0.0\",\"digest\":\"sha256:scheme\"},\"name\":\"ORG\",\"requirementText\":\"Org members only\",\"attributesNeeded\":[\"membership\"],\"dependsOn\":[],\"overrides\":[],\"lines\":[]}]}");
    ROUTES.put(
        "/crosswalk/translate",
        "{\"crosswalk\":{\"kind\":\"crosswalk\",\"id\":\"state-to-district\",\"version\":\"1.0.0\",\"digest\":\"sha256:crosswalk\"},\"sourceScheme\":{\"kind\":\"scheme\",\"id\":\"state\",\"version\":\"1.0.0\",\"digest\":\"sha256:state\"},\"targetScheme\":{\"kind\":\"scheme\",\"id\":\"district\",\"version\":\"1.0.0\",\"digest\":\"sha256:district\"},\"source\":{\"valid\":true,\"inputWasValid\":true,\"rendered\":\"H\",\"assertions\":[],\"errors\":[],\"warnings\":[],\"appliedFixes\":[],\"assessment\":{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]},\"notices\":[]},\"outcomes\":[],\"selections\":[],\"target\":{\"valid\":true,\"inputWasValid\":false,\"rendered\":\"D-H\",\"assertions\":[],\"errors\":[],\"warnings\":[],\"appliedFixes\":[],\"assessment\":{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],\"unrepresentable\":[]},\"notices\":[]},\"status\":\"complete\"}");
    ROUTES.put("/document/audit", documentAuditJson());
    ROUTES.put(
        "/document/assess-access",
        "{\"audit\":"
            + documentAuditJson()
            + ",\"authored\":"
            + scenarioJson("authored", false, "granted")
            + ",\"authoritative\":"
            + scenarioJson("authoritative", false, "denied")
            + ",\"projected\":"
            + scenarioJson("projected", true, "not-evaluated")
            + "}");
  }

  @BeforeAll
  static void startMockServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockExecutor = Executors.newFixedThreadPool(4);
    mockServer.setExecutor(mockExecutor);
    mockServer.createContext("/", new DispatchHandler());
    mockServer.start();
    baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop(0);
    mockExecutor.shutdown();
  }

  private EzClassifyContext connect() {
    return EzClassifyClient.connect(baseUrl, "acme-ctx", "1.0.0");
  }

  private static final class MutableWireNumber extends Number {
    private int value;

    private MutableWireNumber(int value) {
      this.value = value;
    }

    @Override
    public int intValue() {
      return value;
    }

    @Override
    public long longValue() {
      return value;
    }

    @Override
    public float floatValue() {
      return value;
    }

    @Override
    public double doubleValue() {
      return value;
    }

    @Override
    public String toString() {
      return Integer.toString(value);
    }
  }

  @Test
  void numericMetadataMutationCannotChangePublicRequestOrCacheIdentity() {
    var custom = new MutableWireNumber(7);
    var atomic = new java.util.concurrent.atomic.AtomicInteger(13);
    var json = new com.google.gson.JsonObject();
    json.add("atomic", new com.google.gson.JsonPrimitive(atomic));
    var metadata = new java.util.LinkedHashMap<String, Object>();
    metadata.put("custom", custom);
    metadata.put("json", json);
    var fact =
        new Assertion(
            "level",
            "L_H",
            AssertionConfidence.CERTAIN,
            null,
            metadata,
            null,
            null,
            "user",
            null,
            null);
    var whole =
        new ProcessingContext(
            ProcessingRole.PART, new ProcessingContext.PartOf("H", List.of(fact)));
    var process = ProcessOptions.builder().assertions(List.of(fact)).build();
    var validate = ValidateOptions.builder().context(whole).build();
    var scheme = connect().scheme();
    scheme.process("H", process);
    String processBody = lastBody;
    scheme.validate("H", validate);
    String validateBody = lastBody;
    custom.value = 99;
    atomic.set(42);
    // Equal cache keys return without changing the last observed request body.
    scheme.process("H", process);
    assertEquals(validateBody, lastBody);
    scheme.validate("H", validate);
    assertEquals(validateBody, lastBody);
    var returned =
        (com.google.gson.JsonObject) process.assertions().get(0).metadata().get("custom");
    returned.addProperty("value", 55);
    var returnedWhole =
        (com.google.gson.JsonObject)
            validate.context().partOf().assertions().get(0).metadata().get("json");
    returnedWhole.addProperty("atomic", 56);
    scheme.process("H", process);
    assertEquals(validateBody, lastBody);
    scheme.validate("H", validate);
    assertEquals(validateBody, lastBody);
    var uncached =
        new EzClassifyContext(
                baseUrl,
                "acme-ctx",
                "1.0.0",
                null,
                java.net.http.HttpClient.newHttpClient(),
                new ContextCacheOptions(false, true))
            .scheme();
    uncached.process("H", process);
    assertEquals(processBody, lastBody);
    uncached.validate("H", validate);
    assertEquals(validateBody, lastBody);
  }

  @Test
  void mutableMapKeyCannotChangePublicRequestOrCacheIdentity() {
    var key = new MutableWireNumber(7);
    var nested = new java.util.LinkedHashMap<Object, Object>();
    nested.put(key, "value");
    var fact =
        new Assertion(
            "level",
            "L_H",
            AssertionConfidence.CERTAIN,
            null,
            java.util.Map.of("nested", nested),
            null,
            null,
            "user",
            null,
            null);
    var context =
        new ProcessingContext(
            ProcessingRole.PART, new ProcessingContext.PartOf("H", List.of(fact)));
    var process = ProcessOptions.builder().assertions(List.of(fact)).build();
    var validate = ValidateOptions.builder().context(context).build();
    var scheme = connect().scheme();
    scheme.process("H", process);
    String processBody = lastBody;
    assertTrue(processBody.contains("\"nested\":{\"7\":\"value\"}"));
    scheme.validate("H", validate);
    String validateBody = lastBody;
    key.value = 99;
    scheme.process("H", process);
    assertEquals(validateBody, lastBody);
    scheme.validate("H", validate);
    assertEquals(validateBody, lastBody);
    var returned = (java.util.Map<?, ?>) process.assertions().get(0).metadata().get("nested");
    assertEquals(java.util.Set.of("7"), returned.keySet());
    assertThrows(UnsupportedOperationException.class, () -> returned.keySet().clear());
    var returnedWhole =
        (java.util.Map<?, ?>)
            validate.context().partOf().assertions().get(0).metadata().get("nested");
    assertThrows(UnsupportedOperationException.class, () -> returnedWhole.clear());
    var uncached =
        new EzClassifyContext(
                baseUrl,
                "acme-ctx",
                "1.0.0",
                null,
                java.net.http.HttpClient.newHttpClient(),
                new ContextCacheOptions(false, true))
            .scheme();
    uncached.process("H", process);
    assertEquals(processBody, lastBody);
    uncached.validate("H", validate);
    assertEquals(validateBody, lastBody);
  }

  /**
   * Response-shape gate for the marking routes. Every canned answer for a scheme or crosswalk route
   * is one the contract allows, checked with every object closed, so a stub cannot keep a member
   * the contract removed, or miss one it requires, and leave a decoding test passing against a
   * shape no server sends.
   */
  @Test
  void factsSuggestionsSendOnlyAssertionsAndKeepExplicitFalse() {
    var fact =
        new Assertion(
            "level",
            "L_H",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "user",
            null,
            null);
    connect()
        .scheme()
        .suggest(
            List.of(fact),
            FactsSuggestOptions.builder()
                .outputFormat("marking/long")
                .surface("marking")
                .filter("high")
                .includeGated(false)
                .build());
    var body = JsonParser.parseString(lastBody).getAsJsonObject();
    assertFalse(body.has("input"));
    assertFalse(body.has("inputFormat"));
    assertEquals(ContextJson.assertionArray(List.of(fact)), body.getAsJsonArray("assertions"));
    assertEquals("marking/long", body.get("outputFormat").getAsString());
    assertEquals("marking", body.get("surface").getAsString());
    assertEquals("high", body.get("filter").getAsString());
    assertFalse(body.get("includeGated").getAsBoolean());
    assertEquals(
        java.util.Set.of("assertions", "outputFormat", "surface", "filter", "includeGated"),
        body.keySet());
  }

  @Test
  void textSuggestionsKeepExplicitFalseWithoutFactsAndScanSendsOnlyDeclaredFields() {
    connect().scheme().suggest("H", SuggestOptions.builder().includeGated(false).build());
    var suggestions = JsonParser.parseString(lastBody).getAsJsonObject();
    assertEquals(java.util.Set.of("input", "includeGated"), suggestions.keySet());
    assertEquals("H", suggestions.get("input").getAsString());
    assertFalse(suggestions.get("includeGated").getAsBoolean());
    connect()
        .scheme()
        .scan("doc H", ScanOptions.builder().inputFormat("banner").outputFormat("portion").build());
    var scan = JsonParser.parseString(lastBody).getAsJsonObject();
    assertEquals(java.util.Set.of("text", "inputFormat", "outputFormat"), scan.keySet());
    assertEquals("doc H", scan.get("text").getAsString());
  }

  @Test
  void everyCannedMarkingAnswerIsOneTheContractAllows() {
    OpenApiSchemas spec = OpenApiSchemas.load();
    List<String> failures = new ArrayList<>();
    for (String route : new TreeSet<>(ROUTES.keySet())) {
      if (!route.startsWith("/scheme/") && !route.startsWith("/crosswalk/")) continue;
      for (String error :
          spec.validateContextResponse(route.substring(1), JsonParser.parseString(served(route)))) {
        failures.add(route + " " + error);
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  void theResponseGateRefusesAnUndeclaredMemberAndAMissingRequiredOne() {
    // Canned fixtures must contain only declared members and include every required member.
    OpenApiSchemas spec = OpenApiSchemas.load();
    String validate = served("/scheme/validate");
    List<String> undeclared =
        spec.validateContextResponse(
            "scheme/validate",
            JsonParser.parseString(
                validate.replace("\"valid\":", "\"undeclaredFixtureMember\":{},\"valid\":")));
    assertTrue(
        undeclared.stream().anyMatch(e -> e.contains("'undeclaredFixtureMember'")),
        undeclared.toString());
    List<String> missing =
        spec.validateContextResponse(
            "scheme/validate",
            JsonParser.parseString(validate.replace("\"inputWasValid\":false,", "")));
    assertTrue(missing.stream().anyMatch(e -> e.contains("'inputWasValid'")), missing.toString());
  }

  @Test
  void connectToLatestSurfacesResolvedVersion() {
    EzClassifyContext ctx = EzClassifyClient.connect(baseUrl, "acme-ctx", "latest");
    assertEquals("latest", ctx.version());
    ctx.scheme().process("H");
    assertEquals("/v1/ctx/acme-ctx/latest/scheme/process", lastPath);
    assertEquals("9.9.9", ctx.resolvedVersion());
  }

  @Test
  void aContextTokenCanSupplyTheVersionSoPathsCarryNone() {
    EzClassifyContext ctx =
        EzClassifyClient.connect(
            baseUrl, "acme-ctx", null, new RestClientCredentials(null, "signed-context"));
    // The version-less URL lists a Context's versions, so only the description asks for latest.
    assertEquals("/v1/ctx/acme-ctx/latest", lastPath);
    assertNull(ctx.version());
    ctx.scheme().process("H");
    assertEquals("/v1/ctx/acme-ctx/scheme/process", lastPath);
    assertEquals("signed-context", lastContextToken);
    assertEquals("9.9.9", ctx.resolvedVersion());
  }

  @Test
  void anOmittedVersionLetsTheServerChooseWithoutAContextToken() {
    EzClassifyContext ctx = EzClassifyClient.connect(baseUrl, "acme-ctx");
    // The runtime answers latest with the newest published version, the same as no segment.
    assertEquals("GET", lastMethod);
    assertEquals("/v1/ctx/acme-ctx/latest", lastPath);
    assertNull(lastContextToken);
    assertNull(ctx.version());
    ctx.scheme().process("H");
    assertEquals("/v1/ctx/acme-ctx/scheme/process", lastPath);
    assertEquals("9.9.9", ctx.resolvedVersion());

    new EzClassifyClient(baseUrl).context("acme-ctx").scheme().validate("H");
    assertEquals("/v1/ctx/acme-ctx/scheme/validate", lastPath);
  }

  @Test
  void literalNullVersionsCompileAndKeepServerChosenBindings() {
    try (EzClassifyClient client = new EzClassifyClient(baseUrl)) {
      EzClassifyContext context = client.context("acme-ctx", null);
      assertNull(context.version());
      context.scheme().process("H");
      assertEquals("/v1/ctx/acme-ctx/scheme/process", lastPath);

      EzClassifyContext withOptions =
          client.context("acme-ctx", null, new ContextCacheOptions(false, true));
      assertNull(withOptions.version());
      withOptions.scheme().process("H");
      assertEquals("/v1/ctx/acme-ctx/scheme/process", lastPath);
    }

    EzClassifyContext connected = EzClassifyClient.connect(baseUrl, "acme-ctx", null);
    assertNull(connected.version());
    assertEquals("/v1/ctx/acme-ctx/latest", lastPath);

    EzClassifyContext withoutCredentials =
        EzClassifyClient.connect(baseUrl, "acme-ctx", null, null);
    assertNull(withoutCredentials.version());
    assertNull(lastContextToken);

    var credentials = new RestClientCredentials(null, "signed-context");
    EzClassifyContext withCredentials =
        EzClassifyClient.connect(baseUrl, "acme-ctx", null, credentials);
    assertNull(withCredentials.version());
    assertEquals("signed-context", lastContextToken);

    EzClassifyContext withBoth =
        EzClassifyClient.connect(
            baseUrl, "acme-ctx", null, credentials, new ContextCacheOptions(false, true));
    assertNull(withBoth.version());
    withBoth.scheme().process("H");
    assertEquals("/v1/ctx/acme-ctx/scheme/process", lastPath);
    assertEquals("signed-context", lastContextToken);
  }

  @Test
  void aNullOrBlankVersionIsTheSameAsOmittingIt() {
    for (String version : java.util.Arrays.asList(null, "", " ")) {
      EzClassifyContext ctx = EzClassifyClient.connect(baseUrl, "acme-ctx", version);
      assertNull(ctx.version(), String.valueOf(version));
      ctx.scheme().process("H");
      assertEquals("/v1/ctx/acme-ctx/scheme/process", lastPath, String.valueOf(version));
    }
  }

  @Test
  void connectResolvesViaDescribeEndpoint() {
    EzClassifyContext ctx = EzClassifyClient.connect(baseUrl, "acme-ctx", "1.0.0");
    // connect() calls resolve() -> GET /v1/ctx/acme-ctx/1.0.0
    assertEquals("GET", lastMethod);
    assertEquals("/v1/ctx/acme-ctx/1.0.0", lastPath);
    assertEquals("9.9.9", ctx.resolvedVersion());
  }

  @Test
  void credentialsKeepCallerIdentityAndContextAuthorizationIndependent() {
    RestClientCredentials credentials =
        new RestClientCredentials("caller-access", "signed-context");
    EzClassifyClient client = new EzClassifyClient(baseUrl, credentials);

    client.context("acme-ctx", "1.0.0").resolve();
    assertEquals("Bearer caller-access", lastAuthorization);
    assertEquals("signed-context", lastContextToken);

    client.ping();
    assertEquals("Bearer caller-access", lastAuthorization);
    assertNull(lastContextToken);
  }

  @Test
  void connectEncodesSpecialCharsInPath() {
    EzClassifyClient.connect(baseUrl, "tenant a/marking", "1.0.0+gov");
    assertEquals("/v1/ctx/tenant%20a%2Fmarking/1.0.0%2Bgov", lastRawPath);
  }

  @Test
  void schemeProcess() {
    ProcessResult r =
        connect()
            .scheme()
            .process(
                "h",
                ProcessOptions.builder()
                    .outputs(List.of("marking/long", "marking/short"))
                    .suggestFor(List.of("level"))
                    .build())
            .result();
    assertTrue(r.valid());
    assertFalse(r.inputWasValid());
    assertEquals("H", r.rendered());
    assertEquals(1, r.assertions().size());
    assertEquals("A&B", r.assessment().emitted().get(0).value());
    assertEquals(FixAction.REORDER, r.appliedFixes().get(0).action());
    // For a scheme that declares surfaces, recognition reports the view the text was read as, by
    // its output address, and the role that view states.
    assertEquals("marking/long", r.recognition().inputFormat());
    assertEquals(ProcessingRole.WHOLE, r.recognition().role());
    assertEquals(RecognitionStatus.COMPLETE, r.recognition().status());
    assertEquals(RecognitionCorrectionReason.CASE, r.recognition().corrections().get(0).reason());
    assertEquals(List.of("marking/long", "marking/short"), List.copyOf(r.renders().keySet()));
    assertEquals("(H)", r.renders().get("marking/short").rendered());
    assertEquals("A&B", r.renders().get("marking/long").assessment().emitted().get(0).value());
    SuggestResult level = r.suggestionsFor().get("level");
    assertEquals("L_H", level.suggestions().get(0).conceptId());
    assertTrue(level.suggestions().get(0).selected());
    // The process result carries the notices for its own assertions, so a caller that needs them
    // does not follow up with the standalone notices call.
    assertEquals(1, r.notices().size());
    assertEquals("ORG", r.notices().get(0).conceptId());
    assertTrue(r.notices().get(0).required());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/process", lastPath);
    assertEquals(
        JsonParser.parseString(
            "{\"input\":\"h\",\"outputs\":[\"marking/long\",\"marking/short\"],"
                + "\"suggestFor\":[\"level\"]}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeProcessSendsOutputsAndSuggestForOnlyWhenNamed() {
    connect().scheme().process("H");
    assertEquals(JsonParser.parseString("{\"input\":\"H\"}"), JsonParser.parseString(lastBody));

    // An empty list is a choice the caller made, so it reaches the server as one.
    connect()
        .scheme()
        .process("H", ProcessOptions.builder().outputs(List.of()).suggestFor(List.of()).build());
    assertEquals(
        JsonParser.parseString("{\"input\":\"H\",\"outputs\":[],\"suggestFor\":[]}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeProcessSendsTypedTextAndChosenFactsTogether() {
    Assertion chosen =
        new Assertion(
            "level",
            "L_H",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "user",
            null,
            null);
    connect()
        .scheme()
        .process(
            "h//",
            ProcessOptions.builder()
                .inputFormat("marking/long")
                .outputFormat("marking/short")
                .surface("marking")
                .assertions(List.of(chosen))
                .build());
    assertEquals(
        JsonParser.parseString(
            "{\"input\":\"h//\",\"inputFormat\":\"marking/long\","
                + "\"outputFormat\":\"marking/short\",\"surface\":\"marking\","
                + "\"assertions\":[{\"categoryId\":\"level\",\"conceptId\":\"L_H\","
                + "\"confidence\":\"certain\",\"source\":\"user\"}]}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeProcessSendsTheProcessingContextInItsWireShape() {
    ProcessingContext part =
        new ProcessingContext(ProcessingRole.PART, new ProcessingContext.PartOf("H//A", null));
    connect().scheme().process("H", ProcessOptions.builder().context(part).build());
    assertEquals(
        JsonParser.parseString(
            "{\"input\":\"H\",\"context\":{\"role\":\"part\",\"partOf\":{\"rendered\":\"H//A\"}}}"),
        JsonParser.parseString(lastBody));

    ProcessingContext whole = new ProcessingContext(ProcessingRole.WHOLE, null);
    connect().scheme().process("H", ProcessOptions.builder().context(whole).build());
    assertEquals(
        JsonParser.parseString("{\"input\":\"H\",\"context\":{\"role\":\"whole\"}}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeProcessSendsThePartOfAssertionsInTheirFullWireShape() {
    // partOf.assertions is populated by the engine but forwardable verbatim; each entry is the
    // full Assertion shape, not the narrower projection assertionArray() sends for `assertions`.
    Assertion carried =
        new Assertion(
            "level",
            "HIGH",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "parse",
            null,
            "H");
    ProcessingContext part =
        new ProcessingContext(
            ProcessingRole.PART, new ProcessingContext.PartOf("H//A", List.of(carried)));
    connect().scheme().process("H", ProcessOptions.builder().context(part).build());
    assertEquals(
        JsonParser.parseString(
            "{\"input\":\"H\",\"context\":{\"role\":\"part\",\"partOf\":{\"rendered\":\"H//A\","
                + "\"assertions\":[{\"categoryId\":\"level\",\"conceptId\":\"HIGH\","
                + "\"confidence\":\"certain\",\"source\":\"parse\",\"value\":\"H\"}]}}}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeProcessSendsPartsAndOmitsThemWhenNull() {
    connect().scheme().process("H//A", ProcessOptions.builder().parts(List.of("H//B")).build());
    assertEquals(
        JsonParser.parseString("{\"input\":\"H//A\",\"parts\":[\"H//B\"]}"),
        JsonParser.parseString(lastBody));

    connect().scheme().process("H//A", ProcessOptions.builder().build());
    assertEquals(JsonParser.parseString("{\"input\":\"H//A\"}"), JsonParser.parseString(lastBody));
  }

  @Test
  void schemeValidate() {
    ValidateResult r = connect().scheme().validate("H HGIH").result();
    assertFalse(r.valid());
    assertFalse(r.inputWasValid());
    assertEquals("marking/short", r.recognition().inputFormat());
    assertEquals(ProcessingRole.PART, r.recognition().role());
    assertEquals("HGIH", r.recognition().unmatched().get(0).text());
    List<ValidationError> errs = r.errors();
    assertEquals(1, errs.size());
    assertEquals("_unmatched_token", errs.get(0).constraintId());
    // The ids an unread token may have been meant as reach a caller as data, apart from the
    // words in the message. Only their presence means anything: an error without them says
    // nothing about whether the token was stray text.
    assertEquals(List.of("L_H"), errs.get(0).didYouMean());
    assertNull(errs.get(0).suggestedFix());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/validate", lastPath);
    assertEquals(
        JsonParser.parseString("{\"input\":\"H HGIH\"}"), JsonParser.parseString(lastBody));
  }

  @Test
  void schemeValidateSendsFactsWithNoInputBesideThem() {
    Assertion stated =
        new Assertion(
            "level",
            "L_H",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "user",
            null,
            null);
    ProcessingContext whole = new ProcessingContext(ProcessingRole.WHOLE, null);
    connect()
        .scheme()
        .validate(
            List.of(stated),
            FactsValidateOptions.builder()
                .outputFormat("marking/long")
                .surface("marking")
                .context(whole)
                .parts(List.of("(H)"))
                .build());
    assertEquals(
        JsonParser.parseString(
            "{\"assertions\":[{\"categoryId\":\"level\",\"conceptId\":\"L_H\","
                + "\"confidence\":\"certain\",\"source\":\"user\"}],"
                + "\"outputFormat\":\"marking/long\",\"surface\":\"marking\","
                + "\"context\":{\"role\":\"whole\"},\"parts\":[\"(H)\"]}"),
        JsonParser.parseString(lastBody));

    connect().scheme().validate(List.of(stated));
    assertFalse(JsonParser.parseString(lastBody).getAsJsonObject().has("input"));
  }

  @Test
  void schemeValidateSendsContextAndPartsAndOmitsThemWhenNull() {
    ProcessingContext part = new ProcessingContext(ProcessingRole.PART, null);
    connect()
        .scheme()
        .validate(
            "H//A",
            ValidateOptions.builder()
                .inputFormat("short")
                .context(part)
                .parts(List.of("H//B"))
                .build());
    assertEquals(
        JsonParser.parseString(
            "{\"input\":\"H//A\",\"inputFormat\":\"short\","
                + "\"context\":{\"role\":\"part\"},\"parts\":[\"H//B\"]}"),
        JsonParser.parseString(lastBody));

    connect().scheme().validate("H//A", ValidateOptions.builder().build());
    assertEquals(JsonParser.parseString("{\"input\":\"H//A\"}"), JsonParser.parseString(lastBody));
  }

  @Test
  void schemeExplainPostsToItsOwnRouteAndAnswersWithTheTrace() {
    ExplanationTrace trace = connect().scheme().explain("HIGH").result();
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/explain", lastPath);
    assertEquals("HIGH//STAFF", trace.rendered());
    assertEquals("infer", trace.entries().get(0).stage());
    assertEquals(List.of("L_H"), trace.entries().get(0).triggerConceptIds());
    assertEquals("HIGH requires STAFF.", trace.inferences().get(0).explanation());
    assertEquals("L_H", trace.finalAssertions().get(0).conceptId());

    ProcessingContext whole = new ProcessingContext(ProcessingRole.WHOLE, null);
    connect()
        .scheme()
        .explain(
            "HIGH",
            ExplainOptions.builder()
                .inputFormat("long")
                .outputFormat("marking/long")
                .surface("marking")
                .assertions(List.of())
                .context(whole)
                .parts(List.of("(H)"))
                .build());
    assertEquals(
        JsonParser.parseString(
            "{\"input\":\"HIGH\",\"inputFormat\":\"long\",\"outputFormat\":\"marking/long\","
                + "\"surface\":\"marking\",\"assertions\":[],"
                + "\"context\":{\"role\":\"whole\"},\"parts\":[\"(H)\"]}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeSuggest() {
    SuggestResult s = connect().scheme().suggest("L").result();
    assertEquals(1, s.suggestions().size());
    assertEquals("L_H", s.suggestions().get(0).conceptId());
    assertEquals(1, s.warnings().size());
    assertEquals("L_X", s.warnings().get(0).conceptId());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/suggest", lastPath);
  }

  @Test
  void schemeSuggestForCategories() {
    Map<String, SuggestResult> byCategory =
        connect().scheme().suggestForCategories(List.of(), List.of("level", "audience")).result();
    assertEquals(List.of("level", "audience"), List.copyOf(byCategory.keySet()));
    assertEquals("L_H", byCategory.get("level").suggestions().get(0).conceptId());
    assertTrue(byCategory.get("audience").suggestions().isEmpty());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/suggest-for-categories", lastPath);
    assertEquals(
        JsonParser.parseString("{\"assertions\":[],\"categoryIds\":[\"level\",\"audience\"]}"),
        JsonParser.parseString(lastBody));
  }

  @Test
  void schemeSuggestForCategoriesRefusesAnAnswerMissingARequestedCategory() {
    EzClassifyClientException failure =
        assertThrows(
            EzClassifyClientException.class,
            () -> connect().scheme().suggestForCategories(List.of(), List.of("level", "origin")));
    assertTrue(failure.getMessage().contains("origin"), failure.getMessage());
  }

  @Test
  void schemeAggregate() {
    AggregateResult a = connect().scheme().aggregate(List.of("L", "H")).result();
    assertEquals("H", a.rendered());
    assertEquals(1, a.notices().size());
    assertEquals(ConceptNoticeType.HANDLING, a.notices().get(0).type());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/aggregate", lastPath);
    assertTrue(lastBody.contains("\"inputs\""));
  }

  @Test
  void schemeAggregateAssertionsSendsAssertionSets() {
    Assertion high =
        new Assertion(
            "level",
            "L_H",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "parse",
            null,
            null);
    Assertion orig =
        new Assertion(
            "origin",
            "ORIG",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "parse",
            null,
            "ABCXYZ");
    AggregateResult a =
        connect().scheme().aggregateAssertions(List.of(List.of(high), List.of(orig))).result();
    assertEquals("H", a.rendered());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/aggregate", lastPath);
    // The mutually-exclusive assertionSets field carries the parsed sets, not inputs. The nested
    // array shape and the capture value must both survive to the wire.
    assertTrue(lastBody.contains("\"assertionSets\""));
    assertFalse(lastBody.contains("\"inputs\""));
    assertTrue(lastBody.contains("[[{"));
    assertTrue(lastBody.contains("\"value\":\"ABCXYZ\""));
  }

  @Test
  void schemeScan() {
    ScanResponseBody scan = connect().scheme().scan("doc H").result();
    List<ScanResult> m = scan.results();
    assertEquals(1, m.size());
    assertEquals("H", m.get(0).marking());
    assertEquals(ProcessingRole.PART, m.get(0).role());
    // Each region carries what validate answers for it, read in the role it was found in.
    assertEquals("marking/short", m.get(0).type());
    assertEquals("marking/short", m.get(0).result().recognition().inputFormat());
    assertEquals(ProcessingRole.PART, m.get(0).result().recognition().role());
    // The distinction the object exists to carry: this document was read to
    // the end, so an empty list would have meant it was clean.
    assertEquals(ScanStatus.COMPLETE, scan.limits().status());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/scan", lastPath);
  }

  @Test
  void schemeRenderReturnsBareString() {
    List<Assertion> a =
        List.of(
            new Assertion(
                "level",
                "L_H",
                AssertionConfidence.CERTAIN,
                null,
                null,
                null,
                null,
                "parse",
                null,
                null));
    String rendered = connect().scheme().render(a).result().result();
    assertEquals("LEVEL-HIGH", rendered);
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/render", lastPath);
    assertTrue(lastBody.contains("\"conceptId\":\"L_H\""));
    assertFalse(lastBody.contains("schemeId"));
    // The default-format overload sends no format key.
    assertFalse(lastBody.contains("\"format\""));
  }

  @Test
  void schemeRenderThreadsFormat() {
    List<Assertion> a =
        List.of(
            new Assertion(
                "level",
                "L_H",
                AssertionConfidence.CERTAIN,
                null,
                null,
                null,
                null,
                "parse",
                null,
                null));
    var response =
        connect()
            .scheme()
            .render(
                a,
                RenderOptions.builder()
                    .outputFormat("full")
                    .preservation(RenderPreservation.FULL)
                    .build())
            .result();
    String rendered = response.result();
    assertEquals("LEVEL-HIGH", rendered);
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/render", lastPath);
    assertTrue(lastBody.contains("\"outputFormat\":\"full\""));
    assertTrue(lastBody.contains("\"preservation\":\"full\""));
    assertEquals("A&B", response.assessment().emitted().get(0).value());
  }

  @Test
  void schemeRenderFullPreservationRefusalStaysTyped() {
    Assertion assertion =
        new Assertion(
            "authority",
            "REFUSE",
            AssertionConfidence.CERTAIN,
            null,
            null,
            null,
            null,
            "parse",
            null,
            "A&B");
    EzClassifyClientException failure =
        assertThrows(
            EzClassifyClientException.class,
            () ->
                connect()
                    .scheme()
                    .render(
                        List.of(assertion),
                        RenderOptions.builder()
                            .outputFormat("banner")
                            .preservation(RenderPreservation.FULL)
                            .build()));
    assertEquals(400, failure.statusCode());
    assertEquals("RENDER_NOT_PRESERVING", failure.code());
    assertEquals("omitted authority REFUSE=A&B", failure.detail());
  }

  @Test
  void schemeNormalizeUnwrapsResult() {
    String n = connect().scheme().normalize("high").result().result();
    assertEquals("LEVEL-HIGH", n);
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/normalize", lastPath);
  }

  @Test
  void schemeCheckCoverage() {
    CheckCoverageResult c = connect().scheme().checkCoverage(List.of("RED"), "RED//ORG").result();
    assertFalse(c.covered());
    assertEquals(1, c.missingLabels().size());
    assertEquals(List.of(), c.excess());
    assertEquals(List.of(), c.excessLabels());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/check-coverage", lastPath);
  }

  @Test
  void schemeDescribeConceptUsesGetWithQuery() {
    ConceptDescription d = connect().scheme().describeConcept("L_H", "level").result();
    assertEquals("High", d.name());
    assertEquals(1, d.lines().size());
    assertEquals("GET", lastMethod);
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/describe-concept", lastPath);
    assertTrue(lastQuery.contains("conceptId=L_H"));
    assertTrue(lastQuery.contains("categoryId=level"));
  }

  @Test
  void schemeDescribeConceptRejectsBlankIds() {
    EzClassifyContext ctx = connect();
    for (String blank : new String[] {null, "", "   "}) {
      lastPath = null;
      assertThrows(
          IllegalArgumentException.class,
          () -> ctx.scheme().describeConcept(blank, "level"),
          "conceptId: " + blank);
      assertNull(lastPath, "a blank conceptId must be refused before sending a request");

      lastPath = null;
      assertThrows(
          IllegalArgumentException.class,
          () -> ctx.scheme().describeConcept("L_H", blank),
          "categoryId: " + blank);
      assertNull(lastPath, "a blank categoryId must be refused before sending a request");
    }
  }

  @Test
  void schemeDiff() {
    DiffResult d = connect().scheme().diff("A", "B").result();
    assertEquals(1, d.added().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/diff", lastPath);
  }

  @Test
  void schemeFilterConcepts() {
    FilterResult f = connect().scheme().filterConcepts("hi").result();
    assertEquals(2, f.visibleIds().size());
    assertEquals(2, f.categoryCounts().get("level").visible());
    assertEquals(5, f.categoryCounts().get("level").total());
    assertEquals(9, f.total().total());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/filter-concepts", lastPath);
  }

  @Test
  void schemeNotices() {
    List<Notice> n = connect().scheme().notices(List.of()).result();
    assertEquals(1, n.size());
    assertTrue(n.get(0).required());
    assertEquals(ConceptNoticeType.HANDLING, n.get(0).type());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/notices", lastPath);
  }

  @Test
  void schemeView() {
    SchemeViewResponse v = connect().scheme().view().result();
    assertEquals("Scheme", v.name());
    assertEquals(1, v.categories().size());
    assertEquals("Level", v.categories().get(0).label());
    assertEquals(1, v.categories().get(0).items().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/view", lastPath);
  }

  @Test
  void schemeConceptsPreserveCitationFromTheFlatResource() {
    List<ConceptView> concepts = connect().scheme().concepts("source/a");
    assertEquals(2, concepts.size());
    ConceptView concept = concepts.get(0);
    assertEquals("L_H", concept.id());
    assertEquals("Source register, row 42", concept.citation());
    assertNull(concepts.get(1).citation());
    assertEquals("GET", lastMethod);
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/concepts", lastPath);
    assertEquals("scheme=source%2Fa", lastQuery);

    assertEquals(2, connect().scheme().concepts().size());
    assertEquals("", lastQuery);
  }

  @Test
  void schemeApplyPresetPostsThePresetAndAnswersWithAValidateResult() {
    ValidateResult r = connect().scheme().applyPreset("default-label").result();
    assertTrue(r.valid());
    assertEquals("H", r.rendered());
    assertEquals("POST", lastMethod);
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/apply-preset", lastPath);
    assertEquals("", lastQuery);
    assertEquals("{\"presetId\":\"default-label\"}", lastBody);
  }

  @Test
  void schemeApplyPresetSendsOutputFormatInTheBodyAndNothingInTheQuery() {
    connect().scheme().applyPreset("default-label", "banner");
    assertEquals("/v1/ctx/acme-ctx/1.0.0/scheme/apply-preset", lastPath);
    assertEquals("", lastQuery);
    assertEquals("{\"presetId\":\"default-label\",\"outputFormat\":\"banner\"}", lastBody);
  }

  @Test
  void schemeApplyPresetRejectsBlankIdentifiers() {
    ContextScheme scheme = connect().scheme();
    assertThrows(IllegalArgumentException.class, () -> scheme.applyPreset(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.applyPreset(" "));
    assertThrows(IllegalArgumentException.class, () -> scheme.applyPreset(" ", "banner"));
  }

  @Test
  void guideScan() {
    List<GuideScanResult> g = connect().guide().scan("secret", "g1").result();
    assertEquals(1, g.size());
    assertEquals("r1", g.get(0).rule().id());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/guide/scan", lastPath);
    assertFalse(lastBody.contains("schemeId"));
    assertEquals("broker:guide-rule:srcref:x", g.get(0).provenance().providerId());
  }

  @Test
  void guideEvaluate() {
    EvaluationResult e = connect().guide().evaluate("g1", "r1").result();
    assertEquals("H", e.aggregatedMarking());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/guide/evaluate", lastPath);
    assertTrue(lastBody.contains("\"guideId\":\"g1\""));
    assertFalse(lastBody.contains("schemeId"));
  }

  @Test
  void guideEvaluateMany() {
    EvaluationResult e = connect().guide().evaluateMany("r1").result();
    assertEquals("H", e.aggregatedMarking());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/guide/evaluate", lastPath);
    assertTrue(lastBody.contains("\"allGuides\":true"));
    assertTrue(lastBody.contains("\"selectedRuleIds\":[\"r1\"]"));
    assertFalse(lastBody.contains("guideId"));
  }

  /** breakdown and contributingBreakdown carry the entries the server returned. */
  @Test
  void guideEvaluateMaterializesBreakdowns() {
    EvaluationResult e = connect().guide().evaluate("g1", "r1").result();
    assertEquals(1, e.breakdown().size());
    GuideBreakdownEntry entry = e.breakdown().get(0);
    assertEquals("r1", entry.rule().ruleId());
    assertEquals("H", entry.outcome().rendered());
    assertFalse(entry.isComputed());
    assertTrue(entry.wasOverridden());
    assertEquals("r2", entry.overriddenBy().ruleId());
    assertEquals(1, e.contributingBreakdown().size());
    GuideBreakdownEntry contributing = e.contributingBreakdown().get(0);
    assertTrue(contributing.isComputed());
    assertFalse(contributing.wasOverridden());
  }

  @Test
  void guideFilterRules() {
    FilterResult f = connect().guide().filterRules("sec").result();
    assertEquals(1, f.visibleIds().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/guide/filter-rules", lastPath);
  }

  @Test
  void guideDescribeRule() {
    RuleDescription d = connect().guide().describeRule("r1").result();
    assertEquals("marks H", d.outcomeText());
    assertFalse(d.isComputed());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/guide/describe-rule", lastPath);
    assertEquals(
        com.google.gson.JsonParser.parseString(ROUTES.get("/guide/describe-rule")),
        WireJson.toTree(d));
  }

  @Test
  void guideDescriptionRequiresComputedBoolean() {
    String original = ROUTES.get("/guide/describe-rule");
    var fixture = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
    try {
      fixture.remove("isComputed");
      ROUTES.put("/guide/describe-rule", fixture.toString());
      assertThrows(EzClassifyClientException.class, () -> connect().guide().describeRule("r1"));
      fixture.add("isComputed", com.google.gson.JsonNull.INSTANCE);
      ROUTES.put("/guide/describe-rule", fixture.toString());
      assertThrows(EzClassifyClientException.class, () -> connect().guide().describeRule("r1"));
    } finally {
      ROUTES.put("/guide/describe-rule", original);
    }
  }

  @Test
  void guideView() {
    GuideViewResponse v = connect().guide().view("g1").result();
    assertEquals("Guide", v.name());
    assertEquals(1, v.uncategorized().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/guide/view", lastPath);
  }

  @Test
  void accessCheckOmitsSchemeIdUsesUserAttributes() {
    List<Assertion> a =
        List.of(
            new Assertion(
                "level",
                "L_H",
                AssertionConfidence.CERTAIN,
                null,
                null,
                null,
                null,
                "parse",
                null,
                null));
    AccessDecision d = connect().access().check(a, "{\"clearance\":\"HIGH\"}").result();
    assertTrue(d.granted());
    assertEquals(1, d.rules().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/access/check", lastPath);
    assertTrue(lastBody.contains("userAttributes"));
    assertTrue(lastBody.contains("assertions"));
    assertFalse(lastBody.contains("dataAssertions"));
    assertFalse(lastBody.contains("schemeId"));
    assertEquals("broker:access-rule:srcref:x", d.provenance().providerId());
    assertEquals("snap-7", d.provenance().snapshotId());
    assertTrue(d.rules().get(0).activation().matched());
    assertEquals("clearance", d.rules().get(0).comparisons().get(0).left().path());
    assertEquals("sha256:r1", d.rules().get(0).rule().ruleDigest());
  }

  @Test
  void accessCheckAcceptsSubjectAttributesAsAMap() {
    AccessDecision d =
        connect().access().checkByMarking("RED//ORG", Map.of("clearance", "AMBER")).result();
    assertTrue(d.granted());
    assertTrue(lastBody.contains("\"marking\":\"RED//ORG\""));
    assertTrue(lastBody.contains("\"clearance\":\"AMBER\""));
    assertEquals("/v1/ctx/acme-ctx/1.0.0/access/check", lastPath);
  }

  @Test
  void accessCheckSendsContextAttributesOnlyWhenGiven() {
    connect()
        .access()
        .checkByMarking("RED//ORG", Map.of("clearance", "AMBER"), Map.of("project", "p1"));
    assertEquals(
        JsonParser.parseString("{\"project\":\"p1\"}"),
        JsonParser.parseString(lastBody).getAsJsonObject().get("context"));

    connect().access().checkByMarking("RED//ORG", "{\"clearance\":\"AMBER\"}", null);
    assertFalse(JsonParser.parseString(lastBody).getAsJsonObject().has("context"));
  }

  @Test
  void accessRejectsContextThatIsNotAJsonObjectDocument() {
    EzClassifyContext ctx = connect();
    for (String bad : new String[] {"", "   ", "[1,2]", "\"str\"", "7", "{oops"}) {
      lastPath = null;
      assertThrows(
          IllegalArgumentException.class,
          () -> ctx.access().check(List.of(), "{}", bad),
          "check: " + bad);
      assertNull(lastPath, "check must reject " + bad + " before sending a request");

      lastPath = null;
      assertThrows(
          IllegalArgumentException.class,
          () -> ctx.access().checkByMarking("RED//ORG", "{}", bad),
          "checkByMarking: " + bad);
      assertNull(lastPath, "checkByMarking must reject " + bad + " before sending a request");
    }
  }

  @Test
  void accessCheckByMarking() {
    AccessDecision d =
        connect().access().checkByMarking("RED//ORG", "{\"clearance\":\"AMBER\"}").result();
    assertTrue(d.granted());
    assertTrue(lastBody.contains("\"marking\":\"RED//ORG\""));
    assertFalse(lastBody.contains("schemeId"));
  }

  @Test
  void accessRejectsUserAttributesThatAreNotAJsonObjectDocument() {
    EzClassifyContext ctx = connect();
    for (String bad : new String[] {null, "", "   ", "[1,2]", "\"str\"", "7", "{oops"}) {
      lastPath = null;
      assertThrows(
          IllegalArgumentException.class,
          () -> ctx.access().check(List.of(), bad),
          "check: " + bad);
      assertNull(lastPath, "check must reject " + bad + " before sending a request");

      lastPath = null;
      assertThrows(
          IllegalArgumentException.class,
          () -> ctx.access().checkByMarking("RED//ORG", bad),
          "checkByMarking: " + bad);
      assertNull(lastPath, "checkByMarking must reject " + bad + " before sending a request");
    }
  }

  @Test
  void accessDescribeRequirements() {
    AccessRequirementsDescription r = connect().access().describeRequirements("RED//ORG").result();
    assertEquals(1, r.combinedAttributesNeeded().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/access/describe-requirements", lastPath);
  }

  @Test
  void accessDescribeRule() {
    AccessRuleDescription r = connect().access().describeRule("r1").result();
    assertEquals("ORG", r.name());
    assertEquals(1, r.attributesNeeded().size());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/access/describe-rule", lastPath);
  }

  @Test
  void accessListsRulesAndDerivesIds() {
    List<AccessRuleDescription> rules = connect().access().rules();
    assertEquals(1, rules.size());
    assertEquals("ORG", rules.get(0).name());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/access/rules", lastPath);
    assertEquals("GET", lastMethod);
    assertEquals(List.of("r1"), connect().access().ruleIds());
  }

  @Test
  void crosswalkTranslatesMarking() {
    ContextCrosswalkTranslationResult response =
        connect().crosswalk().translate("state-to-district", "H");
    assertEquals("complete", response.result().status());
    assertEquals("D-H", response.result().target().rendered());
    // Source and target carry what validate answers for each side, not an author's result.
    assertTrue(response.result().source().inputWasValid());
    assertFalse(response.result().target().inputWasValid());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/crosswalk/translate", lastPath);
    assertTrue(lastBody.contains("\"crosswalkId\":\"state-to-district\""));
    assertTrue(lastBody.contains("\"marking\":\"H\""));
    assertFalse(lastBody.contains("schemeId"));
  }

  @Test
  void documentAudit() {
    ContextDocumentAuditResult envelope = connect().document().audit("a doc");
    DocumentAuditReport rep = envelope.result();
    assertEquals("RED//ORG", rep.whole());
    assertEquals(1, rep.wholes().size());
    assertEquals(0, rep.wholes().get(0).start());
    assertEquals(8, rep.wholes().get(0).end());
    assertEquals("utf8-bytes", rep.wholes().get(0).offsetEncoding());
    assertEquals(1, rep.parts().size());
    assertEquals(10, rep.parts().get(0).start());
    assertEquals(14, rep.parts().get(0).end());
    assertEquals("utf8-bytes", rep.parts().get(0).offsetEncoding());
    assertEquals("part-1", rep.parts().get(0).scopeId());
    assertTrue(rep.parts().get(0).validation().valid());
    // An audit entry carries the marking's facts and how recognition read it, so a caller that
    // rolls markings up or checks access does not read each marking a second time. valid says the
    // marking stands once settled; inputWasValid says whether it was right as the author wrote it.
    assertFalse(rep.parts().get(0).validation().inputWasValid());
    assertEquals(1, rep.parts().get(0).validation().appliedFixes().size());
    assertEquals("AMBER", rep.parts().get(0).validation().assertions().get(0).conceptId());
    assertEquals(ProcessingRole.PART, rep.parts().get(0).validation().recognition().role());
    assertTrue(rep.wholeValidation().inputWasValid());
    assertEquals("marking/banner", rep.wholeValidation().recognition().inputFormat());
    assertEquals(2, rep.wholeValidation().assertions().size());
    assertNotNull(rep.wholeCoversParts());
    assertEquals(1, rep.unmarkedSpans().size());
    assertEquals(20, rep.unmarkedSpans().get(0).start());
    assertEquals(25, rep.unmarkedSpans().get(0).end());
    assertEquals("utf8-bytes", rep.unmarkedSpans().get(0).offsetEncoding());
    assertEquals(DocumentOverallVerdict.MIXED, rep.overallVerdict());
    assertEquals("sha256:document", rep.documentDigest());
    assertEquals(DocumentResponseProfile.STANDARD, envelope.responseProjection().profile());
    assertFalse(envelope.responseProjection().detailsComplete());
    assertEquals(
        List.of("result.guideAssessment.nominations"),
        envelope.responseProjection().omittedFields());
    assertEquals("/v1/ctx/acme-ctx/1.0.0/document/audit", lastPath);
    assertTrue(lastBody.contains("\"text\":\"a doc\""));
  }

  /** The audit report keeps the scheme vocabulary the wire carries. */
  @Test
  void documentAuditSurfacesVocabulary() {
    DocumentAuditReport rep = connect().document().audit("a doc").result();
    java.util.Map<String, Term> vocabulary = rep.vocabulary();
    assertNotNull(vocabulary, "vocabulary must be parsed, not dropped");
    assertEquals("banner", vocabulary.get("whole").singular());
    assertEquals("banners", vocabulary.get("whole").plural());
    assertEquals("portion", vocabulary.get("part").singular());
    assertEquals("portions", vocabulary.get("part").plural());
  }

  @Test
  void documentAssessAccessPreservesThreeScenarioSemanticsAndProjection() {
    ContextDocumentAccessAssessmentResult envelope =
        connect()
            .document()
            .assessAccess(
                "a doc",
                Map.of("clearance", "HIGH"),
                Map.of("location", "office"),
                null,
                DocumentResponseProfile.SUMMARY);

    assertEquals("granted", envelope.result().authored().status().toWire());
    assertEquals("denied", envelope.result().authoritative().status().toWire());
    assertEquals("not-evaluated", envelope.result().projected().status().toWire());
    assertTrue(envelope.result().projected().advisory());
    assertNull(envelope.result().projected().decision());
    assertEquals("acme-ctx", envelope.resolution().contextId());
    assertEquals("summary", lastBody.contains("\"responseProfile\":\"summary\"") ? "summary" : "");
    assertTrue(lastBody.contains("\"userAttributes\""));
    assertTrue(lastBody.contains("\"context\""));
  }

  @Test
  void ctxOpPropagatesServerError() {
    EzClassifyContext ctx = EzClassifyClient.connect(baseUrl, "acme-ctx", "1.0.0");
    EzClassifyClientException ex =
        assertThrows(EzClassifyClientException.class, () -> ctx.scheme().process("__error__"));
    assertEquals(404, ex.statusCode());
  }

  private static class DispatchHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      String query = exchange.getRequestURI().getRawQuery();
      byte[] reqBytes = exchange.getRequestBody().readAllBytes();
      lastMethod = exchange.getRequestMethod();
      lastPath = path;
      lastRawPath = exchange.getRequestURI().getRawPath();
      lastQuery = query == null ? "" : query;
      lastBody = new String(reqBytes, StandardCharsets.UTF_8);
      lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
      lastContextToken = exchange.getRequestHeaders().getFirst("x-ezclassify-context-token");

      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("X-EzClassify-Resolved-Version", "9.9.9");

      String body = null;
      int status = 200;
      if (lastBody.contains("\"conceptId\":\"REFUSE\"")
          && lastBody.contains("\"preservation\":\"full\"")) {
        status = 400;
        body =
            "{\"error\":\"render would lose facts\",\"code\":\"RENDER_NOT_PRESERVING\",\"detail\":\"omitted authority REFUSE=A&B\"}";
      } else if (lastBody.contains("__error__")) {
        status = 404;
        body = "{\"error\":\"context not found\"}";
      } else {
        for (String route : ROUTES.keySet()) {
          if (path.endsWith(route)) {
            body = served(route);
            break;
          }
        }
        if (body == null) {
          // Health, or the describe endpoint (no op suffix): /v1/ctx/{id}/{version}
          body =
              path.equals("/v1/health")
                  ? ClientStubServer.health(SpecVersion.CLIENT_SPEC_VERSION, "stub")
                  : ClientStubServer.description(path);
        }
      }

      byte[] out = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, out.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(out);
      }
    }
  }

  /**
   * What the stub answers for {@code route}: the canned result in its Context envelope, except for
   * the two routes whose answer is not enveloped.
   */
  private static String served(String route) {
    String result = ROUTES.get(route);
    if (route.equals("/access/rules") || route.equals("/scheme/concepts")) return result;
    return contextEnvelope(result, route.startsWith("/document/"));
  }

  private static String contextEnvelope(String result, boolean documentOperation) {
    String projection =
        documentOperation
            ? ",\"responseProjection\":{\"profile\":\"standard\",\"detailsComplete\":false,\"omittedFields\":[\"result.guideAssessment.nominations\"],\"counts\":{\"nominations\":2,\"evidenceSpans\":3,\"guideUnits\":1,\"accessRuleResults\":3,\"contentRequirements\":0,\"contextRequirements\":0}}"
            : "";
    return "{\"resolution\":{\"contextId\":\"acme-ctx\",\"requestedVersion\":\"1.0.0\",\"resolvedVersion\":\"9.9.9\",\"artifacts\":[],\"sources\":[]},\"result\":"
        + result
        + ",\"operationLimitations\":[]"
        + projection
        + "}";
  }

  private static String documentAuditJson() {
    return "{\"markingRequirements\":{\"whole\":true,\"part\":true},\"whole\":\"RED//ORG\",\"wholes\":[{\"marking\":\"RED//ORG\",\"start\":0,\"end\":8,\"offsetEncoding\":\"utf8-bytes\"}],\"wholeValidation\":{\"assertions\":[{\"categoryId\":\"level\",\"conceptId\":\"RED\",\"confidence\":\"certain\",\"source\":\"parse\"},{\"categoryId\":\"org\",\"conceptId\":\"ORG\",\"confidence\":\"certain\",\"source\":\"parse\"}],\"recognition\":{\"status\":\"complete\",\"inputFormat\":\"marking/banner\",\"role\":\"whole\",\"surface\":\"marking\",\"unmatched\":[]},\"valid\":true,\"inputWasValid\":true,\"errors\":[],\"proposedFixes\":[],\"appliedFixes\":[],\"rendered\":\"RED//ORG\"},\"parts\":[{\"marking\":\"AMBER\",\"text\":\"para\",\"start\":10,\"end\":14,\"offsetEncoding\":\"utf8-bytes\",\"scopeId\":\"part-1\",\"validation\":{\"assertions\":[{\"categoryId\":\"level\",\"conceptId\":\"AMBER\",\"confidence\":\"certain\",\"source\":\"parse\"}],\"recognition\":{\"status\":\"complete\",\"inputFormat\":\"marking/portion\",\"role\":\"part\",\"surface\":\"marking\",\"unmatched\":[]},\"valid\":true,\"inputWasValid\":false,\"errors\":[],\"proposedFixes\":[],\"appliedFixes\":[{\"ruleId\":\"_recognition_case\",\"action\":\"reorder\",\"message\":\"Read `amber` as `AMBER` (case).\"}],\"rendered\":\"AMBER\"}}],\"wholeCoversParts\":{\"covered\":true,\"missing\":[],\"missingLabels\":[],\"excess\":[],\"excessLabels\":[],\"rendered\":\"RED//ORG\"},\"unmarkedSpans\":[{\"start\":20,\"end\":25,\"offsetEncoding\":\"utf8-bytes\",\"text\":\"extra\"}],\"proceduralFlags\":[\"unmarked-content\"],\"overallVerdict\":\"mixed\",\"vocabulary\":{\"whole\":{\"singular\":\"banner\",\"plural\":\"banners\"},\"part\":{\"singular\":\"portion\",\"plural\":\"portions\"}},\"documentDigest\":\"sha256:document\",\"guideAssessment\":null,\"limitations\":[]}";
  }

  private static String scenarioJson(String basis, boolean advisory, String status) {
    String decision = "not-evaluated".equals(status) ? "null" : ROUTES.get("/access/check");
    return "{\"basis\":\""
        + basis
        + "\",\"advisory\":"
        + advisory
        + ",\"status\":\""
        + status
        + "\",\"target\":{\"basis\":\""
        + basis
        + "\",\"marking\":\"RED//ORG\",\"assertions\":[],\"authoredWhole\":\"RED//ORG\",\"authoredParts\":[],\"determinedGuideRequiredWhole\":null,\"projectedGuideRequiredWhole\":null},\"decision\":"
        + decision
        + ",\"contentRequirements\":[],\"contextRequirements\":[],\"limitations\":[]}";
  }
}
