package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ezclassify.rest.contract.Assertion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The public Context operations reject a missing argument at the call.
 *
 * <p>No server is involved: every assertion here must fail before any transport happens, which is
 * the point. Without these guards a null list would reach {@code Json.stringArray} as a raw {@link
 * NullPointerException}, and a null string would be dropped from the request body so the call
 * failed later as a server-side 400.
 */
class ContextArgumentGuardTest {

  /** Bound to a loopback port nothing listens on: a guard that let a call through would hang. */
  private static EzClassifyContext ctx() {
    return new EzClassifyContext("http://127.0.0.1:1", "ctx", "1.0.0", null);
  }

  @Test
  void schemeOperationsRejectMissingArguments() {
    ContextScheme scheme = ctx().scheme();
    assertThrows(IllegalArgumentException.class, () -> scheme.process(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.validate((String) null));
    assertThrows(IllegalArgumentException.class, () -> scheme.validate((List<Assertion>) null));
    assertThrows(IllegalArgumentException.class, () -> scheme.explain(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.suggest((String) null));
    assertThrows(
        IllegalArgumentException.class, () -> scheme.suggestForCategories(null, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> scheme.suggestForCategories(List.of(), null));
    assertThrows(IllegalArgumentException.class, () -> scheme.aggregate(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.aggregateAssertions(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.scan(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.render(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.render(null, RenderOptions.builder().outputFormat("banner").build()));
    assertThrows(IllegalArgumentException.class, () -> scheme.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.checkCoverage(null, "X"));
    assertThrows(IllegalArgumentException.class, () -> scheme.checkCoverage(List.of(), null));
    assertThrows(IllegalArgumentException.class, () -> scheme.filterConcepts(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.notices(null));
    assertThrows(IllegalArgumentException.class, () -> scheme.describeConcept("", "level"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.process(
                null,
                ProcessOptions.builder()
                    .inputFormat("portion")
                    .outputFormat("banner")
                    .surface("portion")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.validate(
                (String) null,
                ValidateOptions.builder()
                    .inputFormat("portion")
                    .outputFormat("banner")
                    .surface("portion")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.validate(
                (List<Assertion>) null,
                FactsValidateOptions.builder().outputFormat("banner").surface("portion").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.explain(
                null,
                ExplainOptions.builder()
                    .inputFormat("portion")
                    .outputFormat("banner")
                    .surface("portion")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.suggest(
                (String) null,
                SuggestOptions.builder()
                    .inputFormat("portion")
                    .outputFormat("banner")
                    .surface("portion")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.suggestForCategories(null, List.of(), "banner", "portion"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.aggregate(
                null,
                AggregateOptions.builder()
                    .inputFormat("portion")
                    .outputFormat("banner")
                    .surface("portion")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.aggregateAssertions(
                null,
                FactsAggregateOptions.builder().outputFormat("banner").surface("portion").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scheme.scan(
                null, ScanOptions.builder().inputFormat("portion").outputFormat("banner").build()));
    assertThrows(IllegalArgumentException.class, () -> scheme.normalize(null, "portion", "banner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.checkCoverage(List.of(), null, "portion", "banner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.process(null, ProcessOptions.builder().build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.validate((String) null, ValidateOptions.builder().build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.validate((List<Assertion>) null, FactsValidateOptions.builder().build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.explain(null, ExplainOptions.builder().build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> scheme.suggest((String) null, SuggestOptions.builder().build()));
    assertThrows(
        IllegalArgumentException.class, () -> scheme.scan(null, ScanOptions.builder().build()));
  }

  @Test
  void guideOperationsRejectMissingArguments() {
    ContextGuide guide = ctx().guide();
    assertThrows(IllegalArgumentException.class, () -> guide.scan(null));
    assertThrows(IllegalArgumentException.class, () -> guide.scan(null, "g1"));
    assertThrows(IllegalArgumentException.class, () -> guide.evaluate(null, "r1"));
    assertThrows(IllegalArgumentException.class, () -> guide.evaluate("  ", "r1"));
    assertThrows(IllegalArgumentException.class, () -> guide.evaluate("g1", (String[]) null));
    assertThrows(IllegalArgumentException.class, () -> guide.filterRules(null));
    assertThrows(IllegalArgumentException.class, () -> guide.describeRule(null));
    assertThrows(IllegalArgumentException.class, () -> guide.describeRule(""));
  }

  @Test
  void accessOperationsRejectMissingArguments() {
    ContextAccess access = ctx().access();
    assertThrows(
        IllegalArgumentException.class, () -> access.check(null, "{\"clearance\":\"HIGH\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> access.check((List<Assertion>) null, Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> access.check(List.of(), (Map<String, Object>) null));
    assertThrows(IllegalArgumentException.class, () -> access.checkByMarking(null, "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> access.checkByMarking("X", (Map<String, Object>) null));
    assertThrows(IllegalArgumentException.class, () -> access.describeRule(" "));
  }

  @Test
  void crosswalkOperationsRejectMissingArguments() {
    ContextCrosswalk crosswalk = ctx().crosswalk();
    assertThrows(IllegalArgumentException.class, () -> crosswalk.translate(null, "X"));
    assertThrows(IllegalArgumentException.class, () -> crosswalk.translate("", "X"));
    assertThrows(IllegalArgumentException.class, () -> crosswalk.translate("cw", (String) null));
    assertThrows(
        IllegalArgumentException.class, () -> crosswalk.translate("cw", (List<Assertion>) null));
  }
}
