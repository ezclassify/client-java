package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.AssertionConfidence;
import com.ezclassify.rest.contract.DocumentResponseProfile;
import com.ezclassify.rest.contract.GuideDeterminationInput;
import com.ezclassify.rest.contract.GuideFindingDecision;
import com.ezclassify.rest.contract.GuideFindingDetermination;
import com.ezclassify.rest.contract.ProcessingContext;
import com.ezclassify.rest.contract.ProcessingRole;
import com.ezclassify.rest.contract.RenderPreservation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Request-shape gate.
 *
 * <p>Every client call that sends a body is driven with every optional argument supplied, and the
 * body it puts on the wire is validated against that operation's request schema in the vendored
 * contract ({@code vendor/spec/openapi.yaml}). The request schemas close their objects ({@code
 * additionalProperties: false}), so a field the contract renamed or removed fails here instead of
 * as a 400 from a live server.
 *
 * <p>The same gate covers the query string. The server refuses a query parameter an operation does
 * not declare with a 400, so every request a call puts on the wire, body-carrying or not, may name
 * only the query parameters the contract declares for that operation.
 *
 * <p>The failure this catches is invisible to the rest of the suite. The contract renamed {@code
 * format} and {@code scope} to {@code outputFormat} and {@code surface} on the operations that read
 * a marking, and a body-substring test that asserts whatever name the client sends stays green
 * either way.
 */
class RequestSchemaConformanceTest {

  private static final String CTX_PREFIX = "/v1/ctx/{ctxId}/{version}/";

  private static final OpenApiSchemas SPEC = OpenApiSchemas.load();

  private static final Assertion LEVEL =
      new Assertion(
          "level", "HIGH", AssertionConfidence.CERTAIN, null, null, null, null, "parse", null, "H");

  private static final GuideDeterminationInput DETERMINATIONS =
      new GuideDeterminationInput(
          List.of(
              new GuideFindingDetermination(
                  "finding-1",
                  "sha256:abc",
                  GuideFindingDecision.ACCEPTED,
                  "Confirmed by analyst",
                  null)),
          List.of());

  private static final ProcessingContext PART_OF =
      new ProcessingContext(ProcessingRole.PART, new ProcessingContext.PartOf("H//A", null));

  private static final List<String> PARTS = List.of("H//B");

  private record Case(String route, Consumer<EzClassifyContext> invoke) {}

  /**
   * One case per client call that sends a body, keyed by a readable name. Each supplies every
   * optional argument the call takes, so the whole option surface reaches the wire. Overloads that
   * build a different body get their own case.
   */
  private static final Map<String, Case> CASES = cases();

  private static Map<String, Case> cases() {
    Map<String, Case> c = new LinkedHashMap<>();
    c.put(
        "scheme.process",
        new Case(
            "scheme/process",
            x ->
                x.scheme()
                    .process(
                        "H//A",
                        ProcessOptions.builder()
                            .inputFormat("portion")
                            .outputFormat("banner")
                            .surface("portion")
                            .assertions(List.of(LEVEL))
                            .context(PART_OF)
                            .parts(PARTS)
                            .outputs(List.of("banner", "portion"))
                            .suggestFor(List.of("level"))
                            .build())));
    c.put(
        "scheme.validate-text",
        new Case(
            "scheme/validate",
            x ->
                x.scheme()
                    .validate(
                        "H//A",
                        ValidateOptions.builder()
                            .inputFormat("portion")
                            .outputFormat("banner")
                            .surface("portion")
                            .context(PART_OF)
                            .parts(PARTS)
                            .build())));
    c.put(
        "scheme.validate-facts",
        new Case(
            "scheme/validate",
            x ->
                x.scheme()
                    .validate(
                        List.of(LEVEL),
                        FactsValidateOptions.builder()
                            .outputFormat("banner")
                            .surface("portion")
                            .context(PART_OF)
                            .parts(PARTS)
                            .build())));
    c.put(
        "scheme.explain",
        new Case(
            "scheme/explain",
            x ->
                x.scheme()
                    .explain(
                        "H//A",
                        ExplainOptions.builder()
                            .inputFormat("portion")
                            .outputFormat("banner")
                            .surface("portion")
                            .assertions(List.of(LEVEL))
                            .context(PART_OF)
                            .parts(PARTS)
                            .build())));
    c.put(
        "scheme.suggest",
        new Case(
            "scheme/suggest",
            x ->
                x.scheme()
                    .suggest(
                        "H//",
                        SuggestOptions.builder()
                            .inputFormat("portion")
                            .outputFormat("banner")
                            .surface("portion")
                            .filter("hi")
                            .includeGated(true)
                            .build())));
    c.put(
        "scheme.suggest-facts",
        new Case(
            "scheme/suggest",
            x ->
                x.scheme()
                    .suggest(
                        List.of(LEVEL),
                        FactsSuggestOptions.builder()
                            .outputFormat("banner")
                            .surface("portion")
                            .filter("hi")
                            .includeGated(true)
                            .build())));
    c.put(
        "scheme.suggest-for-categories",
        new Case(
            "scheme/suggest-for-categories",
            x ->
                x.scheme()
                    .suggestForCategories(
                        List.of(LEVEL), List.of("level", "audience"), "banner", "portion")));
    c.put(
        "scheme.aggregate",
        new Case(
            "scheme/aggregate",
            x ->
                x.scheme()
                    .aggregate(
                        List.of("H", "L"),
                        AggregateOptions.builder()
                            .inputFormat("portion")
                            .outputFormat("banner")
                            .surface("portion")
                            .context(PART_OF)
                            .build())));
    c.put(
        "scheme.aggregate-assertions",
        new Case(
            "scheme/aggregate",
            x ->
                x.scheme()
                    .aggregateAssertions(
                        List.of(List.of(LEVEL)),
                        FactsAggregateOptions.builder()
                            .outputFormat("banner")
                            .surface("portion")
                            .context(PART_OF)
                            .build())));
    c.put(
        "scheme.scan",
        new Case(
            "scheme/scan",
            x ->
                x.scheme()
                    .scan(
                        "text H text",
                        ScanOptions.builder()
                            .inputFormat("portion")
                            .outputFormat("banner")
                            .build())));
    c.put(
        "scheme.render",
        new Case(
            "scheme/render",
            x ->
                x.scheme()
                    .render(
                        List.of(LEVEL),
                        RenderOptions.builder()
                            .outputFormat("banner")
                            .preservation(RenderPreservation.FULL)
                            .build())));
    c.put(
        "scheme.normalize",
        new Case("scheme/normalize", x -> x.scheme().normalize("h", "portion", "banner")));
    c.put(
        "scheme.check-coverage",
        new Case(
            "scheme/check-coverage",
            x -> x.scheme().checkCoverage(List.of("L"), "H", "portion", "banner")));
    c.put("scheme.diff", new Case("scheme/diff", x -> x.scheme().diff("H", "L", "portion")));
    c.put(
        "scheme.filter-concepts",
        new Case("scheme/filter-concepts", x -> x.scheme().filterConcepts("hi")));
    c.put("scheme.notices", new Case("scheme/notices", x -> x.scheme().notices(List.of(LEVEL))));
    c.put("scheme.view", new Case("scheme/view", x -> x.scheme().view()));
    c.put(
        "scheme.apply-preset",
        new Case("scheme/apply-preset", x -> x.scheme().applyPreset("default-label", "banner")));
    c.put("guide.scan", new Case("guide/scan", x -> x.guide().scan("secret text", "g1")));
    c.put("guide.evaluate", new Case("guide/evaluate", x -> x.guide().evaluate("g1", "r1")));
    c.put("guide.evaluateMany", new Case("guide/evaluate", x -> x.guide().evaluateMany("r1")));
    c.put(
        "guide.filter-rules",
        new Case("guide/filter-rules", x -> x.guide().filterRules("sec", "g1")));
    c.put(
        "guide.describe-rule",
        new Case("guide/describe-rule", x -> x.guide().describeRule("r1", "g1")));
    c.put("guide.view", new Case("guide/view", x -> x.guide().view("g1")));
    c.put(
        "access.check-json",
        new Case(
            "access/check",
            x ->
                x.access()
                    .check(List.of(LEVEL), "{\"clearance\":\"high\"}", "{\"orgs\":[\"org-1\"]}")));
    c.put(
        "access.check-map",
        new Case(
            "access/check",
            x ->
                x.access()
                    .check(
                        List.of(LEVEL),
                        Map.<String, Object>of("clearance", "high"),
                        Map.<String, Object>of("orgs", List.of("org-1")))));
    c.put(
        "access.check-by-marking-json",
        new Case(
            "access/check",
            x ->
                x.access()
                    .checkByMarking("H", "{\"clearance\":\"high\"}", "{\"orgs\":[\"org-1\"]}")));
    c.put(
        "access.check-by-marking-map",
        new Case(
            "access/check",
            x ->
                x.access()
                    .checkByMarking(
                        "H",
                        Map.<String, Object>of("clearance", "high"),
                        Map.<String, Object>of("orgs", List.of("org-1")))));
    c.put(
        "access.describe-requirements",
        new Case(
            "access/describe-requirements",
            x -> x.access().describeRequirements("H", List.of(LEVEL))));
    c.put(
        "access.describe-rule",
        new Case("access/describe-rule", x -> x.access().describeRule("rule-1")));
    c.put(
        "crosswalk.translate-marking",
        new Case("crosswalk/translate", x -> x.crosswalk().translate("cw-1", "H")));
    c.put(
        "crosswalk.translate-assertions",
        new Case("crosswalk/translate", x -> x.crosswalk().translate("cw-1", List.of(LEVEL))));
    c.put(
        "document.audit",
        new Case(
            "document/audit",
            x ->
                x.document().audit("document text", DETERMINATIONS, DocumentResponseProfile.FULL)));
    c.put(
        "document.assess-access",
        new Case(
            "document/assess-access",
            x ->
                x.document()
                    .assessAccess(
                        "document text",
                        Map.of("clearance", "high"),
                        Map.of("project", "p1"),
                        DETERMINATIONS,
                        DocumentResponseProfile.FULL)));
    return c;
  }

  static Stream<Arguments> caseNames() {
    return CASES.keySet().stream().map(Arguments::of);
  }

  /** Drive one case through the real client and return the request it sent. */
  private static ClientStubServer.CapturedRequest send(String caseName) throws IOException {
    Case c = CASES.get(caseName);
    // The stub serves no routes, so every call ends in a 404 the client reports as a client
    // exception. Only the request matters here, and the assertions below prove one was sent.
    try (ClientStubServer stub = new ClientStubServer(Map.of())) {
      EzClassifyContext ctx = new EzClassifyContext(stub.url(), "acme-marking", "1.0.0", null);
      try {
        c.invoke().accept(ctx);
      } catch (EzClassifyClientException expected) {
        if (expected.statusCode() != 404) throw expected;
      }
      assertEquals(1, stub.requestCount(), caseName + " must send exactly one request");
      return stub.lastRequest();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("caseNames")
  void requestBodyValidatesAgainstTheContractRequestSchema(String caseName) throws IOException {
    Case c = CASES.get(caseName);
    ClientStubServer.CapturedRequest sent = send(caseName);
    assertEquals("POST", sent.method(), caseName);
    assertEquals("/v1/ctx/acme-marking/1.0.0/" + c.route(), sent.path(), caseName);

    List<String> errors =
        SPEC.validateRequest("post", CTX_PREFIX + c.route(), JsonParser.parseString(sent.body()));
    assertTrue(
        errors.isEmpty(),
        caseName
            + " sent a body the contract rejects:\n  "
            + sent.body()
            + "\n  "
            + String.join("\n  ", errors));
  }

  /**
   * Every operation the contract gives a request body has a case above, so a new operation cannot
   * ship without its body being checked.
   */
  @Test
  void everyContractRequestBodyHasACase() {
    Set<String> covered = new TreeSet<>();
    for (Case c : CASES.values()) covered.add(CTX_PREFIX + c.route());

    Set<String> contractPaths = new TreeSet<>();
    List<String> nonPost = new ArrayList<>();
    for (OpenApiSchemas.Operation op : SPEC.operationsWithBodies()) {
      if (op.method().equals("post")) contractPaths.add(op.path());
      else nonPost.add(op.method() + " " + op.path());
    }

    Set<String> missing = new TreeSet<>(contractPaths);
    missing.removeAll(covered);
    assertTrue(missing.isEmpty(), "No request-schema case for: " + missing);

    Set<String> unknown = new TreeSet<>(covered);
    unknown.removeAll(contractPaths);
    assertTrue(unknown.isEmpty(), "Cases target routes with no contract request body: " + unknown);

    assertTrue(
        nonPost.isEmpty(),
        "The contract now has request bodies on non-POST operations; extend the cases: " + nonPost);
  }

  /**
   * Every field a request schema declares is sent by at least one case.
   *
   * <p>Each case supplies every optional argument its call takes, so a declared field no case sends
   * is a field the client has no argument for. Without this, the contract can add an option and the
   * client can ship without a way to set it while every body it does send still validates.
   */
  @Test
  void everyContractRequestPropertyReachesTheWire() throws IOException {
    Map<String, Set<String>> sent = new TreeMap<>();
    for (Map.Entry<String, Case> c : CASES.entrySet()) {
      JsonObject body = JsonParser.parseString(send(c.getKey()).body()).getAsJsonObject();
      sent.computeIfAbsent(c.getValue().route(), route -> new TreeSet<>()).addAll(body.keySet());
    }
    List<String> unsent = unsentFields(sent);
    assertTrue(
        unsent.isEmpty(),
        "the client never sends these contract fields: " + String.join("; ", unsent));
  }

  /**
   * One {@code route: field, field} entry per route whose request schema declares a field that none
   * of the sent keys cover.
   */
  private static List<String> unsentFields(Map<String, Set<String>> sentByRoute) {
    List<String> unsent = new ArrayList<>();
    for (Map.Entry<String, Set<String>> route : sentByRoute.entrySet()) {
      Set<String> missing =
          new TreeSet<>(SPEC.declaredProperties("post", CTX_PREFIX + route.getKey()));
      missing.removeAll(route.getValue());
      if (!missing.isEmpty()) unsent.add(route.getKey() + ": " + String.join(", ", missing));
    }
    return unsent;
  }

  /** A call that sends no body, with the contract operation it must match. */
  private record GetCase(String path, Function<String, Object> invoke) {}

  private static final Map<String, GetCase> GET_CASES = getCases();

  private static Map<String, GetCase> getCases() {
    Map<String, GetCase> c = new LinkedHashMap<>();
    c.put("health", new GetCase("/v1/health", url -> new EzClassifyClient(url).ping()));
    c.put("ctx-list", new GetCase("/v1/ctx", url -> new EzClassifyClient(url).listContexts()));
    c.put(
        "ctx-versions",
        new GetCase(
            "/v1/ctx/{ctxId}", url -> new EzClassifyClient(url).listContextVersions("acme")));
    c.put(
        "ctx-describe",
        new GetCase(
            "/v1/ctx/{ctxId}/{version}",
            url -> {
              context(url).resolve();
              return null;
            }));
    c.put(
        "scheme.summary",
        new GetCase(CTX_PREFIX + "scheme", url -> context(url).scheme().summary()));
    c.put(
        "scheme.summary-of-scheme",
        new GetCase(CTX_PREFIX + "scheme", url -> context(url).scheme().summary("source/a")));
    c.put(
        "scheme.concepts",
        new GetCase(CTX_PREFIX + "scheme/concepts", url -> context(url).scheme().concepts()));
    c.put(
        "scheme.concepts-of-scheme",
        new GetCase(
            CTX_PREFIX + "scheme/concepts", url -> context(url).scheme().concepts("source/a")));
    c.put(
        "scheme.categories",
        new GetCase(CTX_PREFIX + "scheme/categories", url -> context(url).scheme().categories()));
    c.put(
        "scheme.categories-of-scheme",
        new GetCase(
            CTX_PREFIX + "scheme/categories", url -> context(url).scheme().categories("source/a")));
    c.put(
        "scheme.formats",
        new GetCase(CTX_PREFIX + "scheme/formats", url -> context(url).scheme().formats()));
    c.put(
        "scheme.formats-of-scheme",
        new GetCase(
            CTX_PREFIX + "scheme/formats", url -> context(url).scheme().formats("source/a")));
    c.put(
        "scheme.grammar",
        new GetCase(CTX_PREFIX + "scheme/grammar", url -> context(url).scheme().grammar()));
    c.put(
        "scheme.grammar-of-scheme",
        new GetCase(
            CTX_PREFIX + "scheme/grammar", url -> context(url).scheme().grammar("source/a")));
    c.put(
        "scheme.scopes",
        new GetCase(CTX_PREFIX + "scheme/scopes", url -> context(url).scheme().scopes()));
    c.put(
        "scheme.scopes-of-scheme",
        new GetCase(CTX_PREFIX + "scheme/scopes", url -> context(url).scheme().scopes("source/a")));
    c.put(
        "scheme.label-presets",
        new GetCase(
            CTX_PREFIX + "scheme/label-presets", url -> context(url).scheme().labelPresets()));
    c.put(
        "scheme.describe-concept",
        new GetCase(
            CTX_PREFIX + "scheme/describe-concept",
            url -> context(url).scheme().describeConcept("HIGH", "level")));
    c.put(
        "scheme.describe-concept-in-view",
        new GetCase(
            CTX_PREFIX + "scheme/describe-concept",
            url -> context(url).scheme().describeConcept("HIGH", "level", "portion")));
    c.put(
        "access.rules",
        new GetCase(CTX_PREFIX + "access/rules", url -> context(url).access().rules()));
    return c;
  }

  private static EzClassifyContext context(String url) {
    return new EzClassifyContext(url, "acme-marking", "1.0.0", null);
  }

  /** The names in a raw query string, decoded; empty when there is none. */
  private static Set<String> queryNames(String rawQuery) {
    Set<String> names = new TreeSet<>();
    if (rawQuery == null || rawQuery.isEmpty()) return names;
    for (String pair : rawQuery.split("&")) {
      String name = pair.split("=", 2)[0];
      names.add(URLDecoder.decode(name, StandardCharsets.UTF_8));
    }
    return names;
  }

  /** Every body-carrying call names only query parameters its operation declares. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("caseNames")
  void bodyCarryingRequestsSendOnlyDeclaredQueryParameters(String caseName) throws IOException {
    Case c = CASES.get(caseName);
    Set<String> sent = queryNames(send(caseName).query());
    Set<String> undeclared = new TreeSet<>(sent);
    undeclared.removeAll(SPEC.declaredQueryParameters("post", CTX_PREFIX + c.route()));
    assertTrue(
        undeclared.isEmpty(),
        caseName + " sent query parameters the contract does not declare: " + undeclared);
  }

  static Stream<Arguments> getCaseNames() {
    return GET_CASES.keySet().stream().map(Arguments::of);
  }

  /** Every call without a body names only query parameters its operation declares. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("getCaseNames")
  void bodylessRequestsSendOnlyDeclaredQueryParameters(String caseName) throws IOException {
    GetCase c = GET_CASES.get(caseName);
    try (ClientStubServer stub = new ClientStubServer(Map.of())) {
      try {
        c.invoke().apply(stub.url());
      } catch (EzClassifyClientException expected) {
        if (expected.statusCode() != 404) throw expected;
      }
      assertEquals(1, stub.requestCount(), caseName + " must send exactly one request");
      assertEquals("GET", stub.lastRequest().method(), caseName);
      Set<String> undeclared = queryNames(stub.lastRequest().query());
      undeclared.removeAll(SPEC.declaredQueryParameters("get", c.path()));
      assertTrue(
          undeclared.isEmpty(),
          caseName + " sent query parameters the contract does not declare: " + undeclared);
    }
  }

  @Test
  void declaredQueryParametersComeFromTheContract() {
    assertEquals(
        Set.of(), SPEC.declaredQueryParameters("post", CTX_PREFIX + "scheme/apply-preset"));
    assertEquals(
        Set.of(), SPEC.declaredQueryParameters("get", CTX_PREFIX + "scheme/label-presets"));
    assertEquals(Set.of(), SPEC.declaredQueryParameters("get", "/v1/ctx"));
    assertTrue(
        SPEC.declaredQueryParameters("get", CTX_PREFIX + "scheme/describe-concept")
            .containsAll(Set.of("conceptId", "categoryId", "view")));
    for (String path :
        List.of(
            "scheme",
            "scheme/concepts",
            "scheme/categories",
            "scheme/formats",
            "scheme/grammar",
            "scheme/scopes")) {
      assertEquals(Set.of("scheme"), SPEC.declaredQueryParameters("get", CTX_PREFIX + path), path);
    }
  }

  // Negative controls cover undeclared fields, missing required members, wrong types, and gaps
  // in the request-field coverage inventory.

  @Test
  void coverageCheckReportsADeclaredFieldNoCaseSends() {
    Set<String> declared = SPEC.declaredProperties("post", CTX_PREFIX + "scheme/process");
    Set<String> withoutSuggestFor = new TreeSet<>(declared);
    withoutSuggestFor.remove("suggestFor");
    assertEquals(
        List.of("scheme/process: suggestFor"),
        unsentFields(Map.of("scheme/process", withoutSuggestFor)));
    assertEquals(List.of(), unsentFields(Map.of("scheme/process", declared)));
  }

  @Test
  void declaredPropertiesFollowTheSchemaThroughItsRef() {
    assertEquals(
        Set.of("input", "assertions", "inputFormat", "outputFormat", "context", "surface", "parts"),
        SPEC.declaredProperties("post", CTX_PREFIX + "scheme/validate"));
    assertTrue(
        SPEC.declaredProperties("post", CTX_PREFIX + "scheme/aggregate")
            .containsAll(Set.of("inputs", "assertionSets", "context")));
  }

  @Test
  void validatorRejectsUndeclaredFields() {
    List<String> errors =
        SPEC.validateRequest(
            "post",
            CTX_PREFIX + "scheme/process",
            JsonParser.parseString("{\"input\":\"H\",\"undeclaredRequestMember\":true}"));
    assertTrue(
        errors.stream().anyMatch(e -> e.contains("'undeclaredRequestMember'")), errors.toString());
  }

  @Test
  void validatorRejectsMissingRequiredFieldAndWrongType() {
    List<String> missing =
        SPEC.validateRequest(
            "post",
            CTX_PREFIX + "scheme/process",
            JsonParser.parseString("{\"surface\":\"portion\"}"));
    assertTrue(missing.stream().anyMatch(e -> e.contains("'input'")), missing.toString());

    List<String> wrongType =
        SPEC.validateRequest(
            "post",
            CTX_PREFIX + "scheme/render",
            JsonParser.parseString(
                "{\"assertions\":[{\"categoryId\":\"level\",\"conceptId\":\"HIGH\","
                    + "\"confidence\":\"sure\",\"source\":\"parse\"}]}"));
    assertFalse(wrongType.isEmpty(), "an unknown confidence value must be rejected");

    List<String> notAString =
        SPEC.validateRequest(
            "post",
            CTX_PREFIX + "scheme/process",
            JsonParser.parseString("{\"input\":\"H\",\"outputFormat\":7}"));
    assertFalse(notAString.isEmpty(), "a numeric outputFormat must be rejected");
  }

  @Test
  void validatorAcceptsTheRenamedFields() {
    List<String> errors =
        SPEC.validateRequest(
            "post",
            CTX_PREFIX + "scheme/process",
            JsonParser.parseString(
                "{\"input\":\"H\",\"inputFormat\":\"portion\",\"outputFormat\":\"banner\","
                    + "\"surface\":\"portion\"}"));
    assertTrue(errors.isEmpty(), errors.toString());
  }
}
