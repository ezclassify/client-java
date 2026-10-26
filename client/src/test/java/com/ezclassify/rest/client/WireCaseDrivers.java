package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.DocumentResponseProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Literal reviewed operation choices; private means an existing non-public transport seam. */
final class WireCaseDrivers {
  enum Boundary {
    PUBLIC,
    PRIVATE
  }

  static final Map<String, Boundary> DRIVERS =
      Map.ofEntries(
          Map.entry("health.ok", Boundary.PUBLIC),
          Map.entry("ready.ok", Boundary.PRIVATE),
          Map.entry("ctx-list.ok", Boundary.PUBLIC),
          Map.entry("ctx-versions.ok", Boundary.PUBLIC),
          Map.entry("ctx-describe.ok", Boundary.PUBLIC),
          Map.entry("ctx-document-audit.ok", Boundary.PUBLIC),
          Map.entry("ctx-document-audit.empty-text", Boundary.PRIVATE),
          Map.entry("ctx-document-assess-access.ok", Boundary.PUBLIC),
          Map.entry("ctx-describe.presets", Boundary.PUBLIC),
          Map.entry("ctx-describe.crosswalks", Boundary.PUBLIC),
          Map.entry("ctx-scheme-summary.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-concepts.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-categories.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-summary.layered", Boundary.PUBLIC),
          Map.entry("ctx-scheme-concepts.layered", Boundary.PUBLIC),
          Map.entry("ctx-scheme-categories.layered", Boundary.PUBLIC),
          Map.entry("ctx-scheme-formats.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-grammar.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-scopes.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-scopes.none", Boundary.PUBLIC),
          Map.entry("ctx-scheme-label-presets.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-apply-preset.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-apply-preset.unknown", Boundary.PUBLIC),
          Map.entry("ctx-scheme-summary.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-concepts.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-categories.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-formats.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-grammar.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-scopes.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-label-presets.full", Boundary.PUBLIC),
          Map.entry("ctx-scheme-process.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-process.empty", Boundary.PUBLIC),
          Map.entry("ctx-scheme-process.outputs-and-options", Boundary.PUBLIC),
          Map.entry("ctx-scheme-validate.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-validate.assertions", Boundary.PUBLIC),
          Map.entry("ctx-scheme-validate.both-sources", Boundary.PRIVATE),
          Map.entry("ctx-scheme-validate.no-source", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-format", Boundary.PUBLIC),
          Map.entry("ctx-scheme-aggregate.input-format-with-sets", Boundary.PRIVATE),
          Map.entry("ctx-scheme-explain.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-normalize.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-render.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-render.empty", Boundary.PUBLIC),
          Map.entry("ctx-scheme-diff.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-view.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-notices.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-filter-concepts.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-filter-concepts.no-match", Boundary.PUBLIC),
          Map.entry("ctx-scheme-scan.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-aggregate.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-aggregate.assertion-sets", Boundary.PUBLIC),
          Map.entry("ctx-scheme-suggest.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-suggest.gated-hidden", Boundary.PUBLIC),
          Map.entry("ctx-scheme-suggest.gated-included", Boundary.PUBLIC),
          Map.entry("ctx-scheme-suggest.facts", Boundary.PUBLIC),
          Map.entry("ctx-scheme-suggest.both-sources", Boundary.PRIVATE),
          Map.entry("ctx-scheme-suggest.no-source", Boundary.PRIVATE),
          Map.entry("ctx-scheme-suggest.input-format-with-facts", Boundary.PRIVATE),
          Map.entry("ctx-scheme-suggest-for-categories.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-suggest-for-categories.empty-baseline", Boundary.PUBLIC),
          Map.entry("ctx-scheme-check-coverage.ok", Boundary.PUBLIC),
          Map.entry("ctx-scheme-check-coverage.excess", Boundary.PUBLIC),
          Map.entry("ctx-scheme-check-coverage.pipeline", Boundary.PUBLIC),
          Map.entry("ctx-scheme-check-coverage.pipeline-narrowed", Boundary.PUBLIC),
          Map.entry("ctx-scheme-describe-concept.unknown-concept", Boundary.PUBLIC),
          Map.entry("ctx-scheme-describe-concept.ok", Boundary.PUBLIC),
          Map.entry("ctx-access-check.marking", Boundary.PUBLIC),
          Map.entry("ctx-access-check.assertions", Boundary.PUBLIC),
          Map.entry("ctx-access-check.two-attributes", Boundary.PUBLIC),
          Map.entry("ctx-access-describe-rule.ok", Boundary.PUBLIC),
          Map.entry("ctx-access-describe-requirements.ok", Boundary.PUBLIC),
          Map.entry("ctx-access-rules.ok", Boundary.PUBLIC),
          Map.entry("ctx-access-rules.no-policy", Boundary.PUBLIC),
          Map.entry("ctx-guide-scan.ok", Boundary.PUBLIC),
          Map.entry("ctx-guide-evaluate.ok", Boundary.PUBLIC),
          Map.entry("ctx-guide-evaluate.all-guides", Boundary.PUBLIC),
          Map.entry("ctx-guide-describe-rule.ok", Boundary.PUBLIC),
          Map.entry("ctx-guide-describe-rule.unknown-guide", Boundary.PUBLIC),
          Map.entry("ctx-guide-view.by-id", Boundary.PUBLIC),
          Map.entry("ctx-guide-filter-rules.ok", Boundary.PUBLIC),
          Map.entry("ctx-guide-view.ok", Boundary.PUBLIC),
          Map.entry("ctx-crosswalk-translate.marking", Boundary.PUBLIC),
          Map.entry("ctx-crosswalk-translate.assertions", Boundary.PUBLIC),
          Map.entry("ctx-crosswalk-translate.translated", Boundary.PUBLIC),
          Map.entry("ctx-crosswalk-translate.forbidden", Boundary.PUBLIC),
          Map.entry("ctx-crosswalk-translate.both-sources", Boundary.PRIVATE),
          Map.entry("ctx-crosswalk-translate.no-source", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-fix", Boundary.PRIVATE),
          Map.entry(
              "ctx-scheme-process.undeclared-request-field-preserveDeprecated", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-normalization", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-skipRepair", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-skipInference", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-tolerant", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-validateOnly", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-scope", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-format", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-repairStrategy", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-request-field-unknownField", Boundary.PRIVATE),
          Map.entry("ctx-scheme-validate.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-explain.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-normalize.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-aggregate.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-suggest.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-suggest-for-categories.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-render.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-scan.undeclared-field", Boundary.PRIVATE),
          Map.entry("ctx-document-audit.undeclared-guide-field", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.missing-input", Boundary.PRIVATE),
          Map.entry("ctx-scheme-label-presets.undeclared-query-parameter", Boundary.PRIVATE),
          Map.entry("ctx-scheme-process.undeclared-query-parameter", Boundary.PRIVATE),
          Map.entry("health.undeclared-query-parameter", Boundary.PRIVATE),
          Map.entry("denial.authenticated-no-credential", Boundary.PUBLIC),
          Map.entry("denial.authenticated-bad-credential", Boundary.PUBLIC),
          Map.entry("denial.authenticated-health-still-open", Boundary.PUBLIC),
          Map.entry("denial.context-without-carrier", Boundary.PUBLIC),
          Map.entry("denial.ctx-scheme-without-carrier", Boundary.PUBLIC),
          Map.entry("route-miss.route-not-found", Boundary.PRIVATE),
          Map.entry("route-miss.ctx-operation-not-found", Boundary.PRIVATE),
          Map.entry("method-guard.allow-header", Boundary.PRIVATE));

  /**
   * The body breaks the closed request contract: both sources or neither, a member the contract
   * does not declare, or a missing input. The public API cannot build it.
   */
  static final String CLOSED_REQUEST =
      "The public API cannot build a body the closed request contract refuses; the raw seam pins how"
          + " the server's refusal decodes.";

  static final String UNDECLARED_QUERY =
      "The public API cannot send an undeclared query parameter; the raw seam pins how its 400"
          + " decodes.";

  static final String UNDECLARED_ROUTE =
      "No public method sends a route or method the contract does not declare; the raw seam pins"
          + " how the 404 or 405 decodes.";

  static final String NO_SDK_OPERATION =
      "getReady is an orchestrator readiness probe with no SDK method; ping() sends getHealth,"
          + " which carries the version handshake a caller needs.";

  /** Why each privately driven case cannot go through the public API. */
  static final Map<String, String> PRIVATE_REASONS =
      Map.ofEntries(
          Map.entry(
              "ctx-document-audit.empty-text",
              "Literal request omits responseProfile; public audit intentionally supplies STANDARD."),
          Map.entry("ready.ok", NO_SDK_OPERATION),
          Map.entry("ctx-scheme-validate.both-sources", CLOSED_REQUEST),
          Map.entry("ctx-scheme-validate.no-source", CLOSED_REQUEST),
          Map.entry("ctx-scheme-aggregate.input-format-with-sets", CLOSED_REQUEST),
          Map.entry("ctx-scheme-suggest.both-sources", CLOSED_REQUEST),
          Map.entry("ctx-scheme-suggest.no-source", CLOSED_REQUEST),
          Map.entry("ctx-scheme-suggest.input-format-with-facts", CLOSED_REQUEST),
          Map.entry("ctx-crosswalk-translate.both-sources", CLOSED_REQUEST),
          Map.entry("ctx-crosswalk-translate.no-source", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-fix", CLOSED_REQUEST),
          Map.entry(
              "ctx-scheme-process.undeclared-request-field-preserveDeprecated", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-normalization", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-skipRepair", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-skipInference", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-tolerant", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-validateOnly", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-scope", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-format", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-repairStrategy", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.undeclared-request-field-unknownField", CLOSED_REQUEST),
          Map.entry("ctx-scheme-validate.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-explain.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-normalize.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-aggregate.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-suggest.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-suggest-for-categories.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-render.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-scan.undeclared-field", CLOSED_REQUEST),
          Map.entry("ctx-document-audit.undeclared-guide-field", CLOSED_REQUEST),
          Map.entry("ctx-scheme-process.missing-input", CLOSED_REQUEST),
          Map.entry("ctx-scheme-label-presets.undeclared-query-parameter", UNDECLARED_QUERY),
          Map.entry("ctx-scheme-process.undeclared-query-parameter", UNDECLARED_QUERY),
          Map.entry("health.undeclared-query-parameter", UNDECLARED_QUERY),
          Map.entry("route-miss.route-not-found", UNDECLARED_ROUTE),
          Map.entry("route-miss.ctx-operation-not-found", UNDECLARED_ROUTE),
          Map.entry("method-guard.allow-header", UNDECLARED_ROUTE));

  static RestClientCredentials credentials(WireCorpus.Case c) {
    return switch (c.credential()) {
      case "none" -> RestClientCredentials.none();
      case "context-token" -> new RestClientCredentials(null, "fixture-context-carrier");
      case "invalid-bearer" ->
          new RestClientCredentials("fixture-refused-bearer", "fixture-context-carrier");
      default -> throw new AssertionError("unknown credential class");
    };
  }

  static Object invoke(WireCorpus.Case c, EzClassifyContext ctx) {
    String[] pieces = c.target().split("/", 6);
    String op = pieces.length == 6 ? pieces[5] : "";
    if (DRIVERS.get(c.id()) == Boundary.PRIVATE) {
      if ("GET".equals(c.method()) && op.startsWith("scheme/label-presets"))
        return ctx.getArray(op);
      return "GET".equals(c.method())
          ? ctx.getJson(op)
          : ctx.postJson(op, c.body().getAsJsonObject());
    }
    if ("GET".equals(c.method())) {
      if (op.isEmpty()) return ctx.describeContext();
      switch (op) {
        case "scheme" -> {
          return ctx.scheme().summary();
        }
        case "scheme/concepts" -> {
          return ctx.scheme().concepts();
        }
        case "scheme/categories" -> {
          return ctx.scheme().categories();
        }
        case "scheme/formats" -> {
          return ctx.scheme().formats();
        }
        case "scheme/grammar" -> {
          return ctx.scheme().grammar();
        }
        case "scheme/scopes" -> {
          return ctx.scheme().scopes();
        }
        case "scheme/label-presets" -> {
          return ctx.scheme().labelPresets();
        }
        default -> {}
      }
      if (op.equals("access/rules")) return ctx.access().rules();
      if (op.startsWith("scheme/describe-concept?")) {
        // These two recorded values are literal non-encoded ASCII ids; request matching stays raw.
        Map<String, String> query = new java.util.HashMap<>();
        for (String pair : op.substring(op.indexOf('?') + 1).split("&")) {
          String[] kv = pair.split("=", 2);
          query.put(kv[0], kv[1]);
        }
        return ctx.scheme().describeConcept(query.get("conceptId"), query.get("categoryId"));
      }
      throw new AssertionError("unowned public GET " + c.id());
    }
    JsonObject body = c.body().getAsJsonObject();
    ContextScheme s = ctx.scheme();
    return switch (op) {
      case "scheme/process" ->
          s.process(
              text(body, "input"),
              ProcessOptions.builder()
                  .outputFormat(optional(body, "outputFormat"))
                  .outputs(strings(body, "outputs"))
                  .suggestFor(strings(body, "suggestFor"))
                  .build());
      case "scheme/validate" ->
          body.has("assertions")
              ? s.validate(facts(body), FactsValidateOptions.builder().build())
              : s.validate(text(body, "input"));
      case "scheme/suggest" ->
          body.has("assertions")
              ? s.suggest(facts(body))
              : s.suggest(
                  text(body, "input"),
                  SuggestOptions.builder()
                      .filter(optional(body, "filter"))
                      .includeGated(
                          body.has("includeGated") ? body.get("includeGated").getAsBoolean() : null)
                      .build());
      case "scheme/suggest-for-categories" ->
          s.suggestForCategories(facts(body), strings(body, "categoryIds"));
      case "scheme/apply-preset" -> s.applyPreset(text(body, "presetId"));
      case "scheme/explain" -> s.explain(text(body, "input"));
      case "scheme/normalize" -> s.normalize(text(body, "input"));
      case "scheme/render" ->
          s.render(
              facts(body),
              RenderOptions.builder().outputFormat(optional(body, "outputFormat")).build());
      case "scheme/diff" -> s.diff(text(body, "a"), text(body, "b"));
      case "scheme/view" -> s.view();
      case "scheme/notices" -> s.notices(facts(body));
      case "scheme/filter-concepts" -> s.filterConcepts(text(body, "query"));
      case "scheme/scan" -> s.scan(text(body, "text"));
      case "scheme/aggregate" ->
          body.has("assertionSets")
              ? s.aggregateAssertions(assertionSets(body))
              : s.aggregate(strings(body, "inputs"));
      case "scheme/check-coverage" ->
          s.checkCoverage(strings(body, "children"), text(body, "parent"));
      case "access/check" ->
          body.has("assertions")
              ? ctx.access().check(facts(body), body.get("userAttributes").toString())
              : ctx.access()
                  .checkByMarking(text(body, "marking"), body.get("userAttributes").toString());
      case "access/describe-rule" -> ctx.access().describeRule(text(body, "ruleId"));
      case "access/describe-requirements" ->
          ctx.access().describeRequirements(text(body, "marking"));
      case "guide/scan" -> ctx.guide().scan(text(body, "text"));
      case "guide/evaluate" ->
          body.has("allGuides")
              ? ctx.guide().evaluateMany(strings(body, "selectedRuleIds").toArray(String[]::new))
              : ctx.guide()
                  .evaluate(
                      text(body, "guideId"),
                      strings(body, "selectedRuleIds").toArray(String[]::new));
      case "guide/describe-rule" ->
          ctx.guide().describeRule(text(body, "ruleId"), optional(body, "guideId"));
      case "guide/view" -> ctx.guide().view(optional(body, "guideId"));
      case "guide/filter-rules" -> ctx.guide().filterRules(text(body, "query"));
      case "crosswalk/translate" ->
          body.has("assertions")
              ? ctx.crosswalk().translate(text(body, "crosswalkId"), facts(body))
              : ctx.crosswalk().translate(text(body, "crosswalkId"), text(body, "marking"));
      case "document/audit" ->
          ctx.document()
              .audit(
                  text(body, "text"),
                  null,
                  DocumentResponseProfile.fromWire(text(body, "responseProfile")));
      case "document/assess-access" ->
          ctx.document()
              .assessAccess(
                  text(body, "text"),
                  attributes(body),
                  null,
                  null,
                  DocumentResponseProfile.fromWire(text(body, "responseProfile")));
      default -> throw new AssertionError("unowned public POST " + c.id());
    };
  }

  /**
   * Call the public method that sends the case's recorded request, departing from it the way a
   * cache-conformance plan asks. {@code reorderedMembers} supplies the same attributes in the
   * reverse order, and {@code unsetMember} names a member the recorded request leaves out, which
   * every driver here already passes as unset.
   */
  static Object invoke(WireCorpus.Case c, EzClassifyContext ctx, String variant, String member) {
    if (variant == null) return invoke(c, ctx);
    JsonObject body = c.body().getAsJsonObject();
    switch (variant) {
      case "unsetMember" -> {
        if (body.has(member)) throw new AssertionError(c.id() + " sets " + member);
        return invoke(c, ctx);
      }
      case "reorderedMembers" -> {
        if (!c.target().endsWith("/access/check") || !body.has("marking"))
          throw new AssertionError("no reordered-member driver for " + c.id());
        Map<String, Object> reversed = new java.util.LinkedHashMap<>();
        List<Map.Entry<String, Object>> entries = new ArrayList<>(attributes(body).entrySet());
        java.util.Collections.reverse(entries);
        for (var entry : entries) reversed.put(entry.getKey(), entry.getValue());
        return ctx.access().checkByMarking(text(body, "marking"), reversed);
      }
      default -> throw new AssertionError("unsupported variant " + variant);
    }
  }

  static Object invoke(WireCorpus.Case c, EzClassifyClient client) throws Exception {
    if (DRIVERS.get(c.id()) == Boundary.PRIVATE) {
      var seam = EzClassifyClient.class.getDeclaredMethod("getJson", String.class);
      seam.setAccessible(true);
      try {
        return seam.invoke(client, c.target());
      } catch (InvocationTargetException error) {
        throw (RuntimeException) error.getCause();
      }
    }
    return switch (c.target()) {
      case "/v1/health" -> client.ping();
      case "/v1/ctx" -> client.listContexts();
      default -> client.listContextVersions(c.target().substring(c.target().lastIndexOf('/') + 1));
    };
  }

  private static String text(JsonObject body, String name) {
    return body.get(name).getAsString();
  }

  private static String optional(JsonObject body, String name) {
    return body.has(name) ? text(body, name) : null;
  }

  private static List<String> strings(JsonObject body, String name) {
    if (!body.has(name)) return null;
    List<String> result = new ArrayList<>();
    for (JsonElement value : body.getAsJsonArray(name)) result.add(value.getAsString());
    return List.copyOf(result);
  }

  private static List<Assertion> facts(JsonObject body) {
    return WireJson.listFromTree(body.get("assertions"), Assertion.class);
  }

  private static List<List<Assertion>> assertionSets(JsonObject body) {
    List<List<Assertion>> sets = new ArrayList<>();
    for (JsonElement set : body.getAsJsonArray("assertionSets"))
      sets.add(WireJson.listFromTree(set, Assertion.class));
    return List.copyOf(sets);
  }

  private static Map<String, Object> attributes(JsonObject body) {
    return Json.toMap(body.getAsJsonObject("userAttributes"));
  }
}
