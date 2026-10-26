package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** Pins the plan-to-wire mapping without computing or rewriting recorded engine answers. */
class ConformanceMappingTest {
  private record Mapping(String name, String method, String args, String route, String body) {}

  @TestFactory
  Stream<DynamicTest> declaredOperandsAndOptionsReachTheirWireFields() {
    return Stream.of(
            new Mapping(
                "process facts and options",
                "process",
                "[[{\"conceptId\":\"A\"}],{\"context\":{\"role\":\"part\"},\"parts\":[\"P\"],\"outputs\":[\"full\"]}]",
                "scheme/process",
                "{\"input\":\"\",\"assertions\":[{\"conceptId\":\"A\"}],\"context\":{\"role\":\"part\"},\"parts\":[\"P\"],\"outputs\":[\"full\"]}"),
            new Mapping(
                "process text and chosen facts",
                "process",
                "[\"T\",{\"assertions\":[{\"conceptId\":\"A\"}],\"parts\":[\"P\"]}]",
                "scheme/process",
                "{\"input\":\"T\",\"assertions\":[{\"conceptId\":\"A\"}],\"parts\":[\"P\"]}"),
            new Mapping(
                "validate facts omit text",
                "validate",
                "[[{\"conceptId\":\"A\"}],{\"surface\":\"s\",\"context\":{\"partOf\":{\"rendered\":\"W\"},\"role\":\"part\"}}]",
                "scheme/validate",
                "{\"assertions\":[{\"conceptId\":\"A\"}],\"surface\":\"s\",\"context\":{\"partOf\":{\"rendered\":\"W\"},\"role\":\"part\"}}"),
            new Mapping(
                "validate empty facts remain present",
                "validate",
                "[[]]",
                "scheme/validate",
                "{\"assertions\":[]}"),
            new Mapping(
                "validate text role and view",
                "validate",
                "[\"T\",{\"inputFormat\":\"s/view\",\"parts\":[\"P\"],\"outputFormat\":\"full\",\"context\":{\"role\":\"part\"}}]",
                "scheme/validate",
                "{\"input\":\"T\",\"inputFormat\":\"s/view\",\"parts\":[\"P\"],\"outputFormat\":\"full\",\"context\":{\"role\":\"part\"}}"),
            new Mapping(
                "suggest facts omit text",
                "suggest",
                "[[{\"conceptId\":\"A\"}],{\"surface\":\"s\",\"outputFormat\":\"full\"}]",
                "scheme/suggest",
                "{\"assertions\":[{\"conceptId\":\"A\"}],\"surface\":\"s\",\"outputFormat\":\"full\"}"),
            new Mapping(
                "suggest empty facts remain present",
                "suggest",
                "[[]]",
                "scheme/suggest",
                "{\"assertions\":[]}"),
            new Mapping(
                "suggest text carries options",
                "suggest",
                "[\"T\",{\"inputFormat\":\"full\",\"categoryId\":\"c\",\"context\":{\"role\":\"whole\"}}]",
                "scheme/suggest",
                "{\"input\":\"T\",\"inputFormat\":\"full\",\"categoryId\":\"c\",\"context\":{\"role\":\"whole\"}}"),
            new Mapping(
                "aggregate text carries options",
                "aggregate",
                "[[\"T\"],{\"outputFormat\":\"full\",\"surface\":\"s\",\"context\":{\"role\":\"whole\"}}]",
                "scheme/aggregate",
                "{\"inputs\":[\"T\"],\"outputFormat\":\"full\",\"surface\":\"s\",\"context\":{\"role\":\"whole\"}}"),
            new Mapping(
                "aggregate facts carries options",
                "aggregateAssertions",
                "[[[{\"conceptId\":\"A\"}]],{\"outputFormat\":\"full\",\"surface\":\"s\"}]",
                "scheme/aggregate",
                "{\"assertionSets\":[[{\"conceptId\":\"A\"}]],\"outputFormat\":\"full\",\"surface\":\"s\"}"),
            new Mapping(
                "render positional format remains format",
                "render",
                "[[],\"full\"]",
                "scheme/render",
                "{\"assertions\":[],\"format\":\"full\"}"),
            new Mapping(
                "diff uses third argument options",
                "diff",
                "[\"A\",\"B\",{\"inputFormat\":\"full\"}]",
                "scheme/diff",
                "{\"a\":\"A\",\"b\":\"B\",\"inputFormat\":\"full\"}"),
            new Mapping(
                "normalize preserves its input format",
                "normalize",
                "[\"T\",{\"inputFormat\":\"full\"}]",
                "scheme/normalize",
                "{\"input\":\"T\",\"inputFormat\":\"full\"}"),
            new Mapping(
                "scan preserves its input format",
                "scan",
                "[\"T\",{\"inputFormat\":\"full\"}]",
                "scheme/scan",
                "{\"text\":\"T\",\"inputFormat\":\"full\"}"))
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.name(),
                    () -> {
                      String[] actual = map(c.method(), c.args());
                      assertEquals("/v1/ctx/conformance-scheme/1.0.0/" + c.route(), actual[0]);
                      assertEquals(Replayer.parseJson(c.body()), Replayer.parseJson(actual[1]));
                    }));
  }

  @TestFactory
  Stream<DynamicTest> conflictingOperandOverridesAreRefused() {
    return Stream.of("[\"T\",{\"input\":\"other\"}]")
        .map(
            args ->
                DynamicTest.dynamicTest(
                    args,
                    () ->
                        assertThrows(IllegalArgumentException.class, () -> map("process", args))));
  }

  @Test
  void differentWholeRecordingAnswersCannotOverwrite(@TempDir Path dir) throws Exception {
    record(dir, "a", "{\"valid\":true,\"errors\":[]}");
    record(dir, "b", "{\"valid\":true,\"errors\":[],\"extra\":true}");
    assertThrows(IllegalArgumentException.class, () -> load(dir));
  }

  @Test
  void equivalentWholeRecordingAnswersRemainExecutable(@TempDir Path dir) throws Exception {
    record(dir, "a", "{\"valid\":true,\"n\":1}");
    record(dir, "b", "{\"n\":1.0,\"valid\":true}");
    assertEquals(1, load(dir).size());
  }

  @Test
  void arrayOrderInRecordingAnswersRemainsSignificant(@TempDir Path dir) throws Exception {
    record(dir, "a", "{\"items\":[\"a\",\"b\"]}");
    record(dir, "b", "{\"items\":[\"b\",\"a\"]}");
    assertThrows(IllegalArgumentException.class, () -> load(dir));
  }

  private static String[] map(String method, String args) throws Exception {
    Method mapping =
        ConformanceTest.class.getDeclaredMethod(
            "buildPlanBody", String.class, List.class, String.class, String.class, Map.class);
    mapping.setAccessible(true);
    return (String[])
        invoke(mapping, method, Replayer.parseJson(args), "scheme", "guide", Map.of("id", "guide"));
  }

  private static Map<?, ?> load(Path dir) throws Exception {
    Method loading = ConformanceTest.class.getDeclaredMethod("loadAllRecordings", Path.class);
    loading.setAccessible(true);
    return (Map<?, ?>) invoke(loading, dir);
  }

  private static Object invoke(Method method, Object... args) throws Exception {
    try {
      return method.invoke(null, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) throw cause;
      throw e;
    }
  }

  private static void record(Path dir, String name, String answer) throws Exception {
    Files.writeString(
        dir.resolve(name + ".recordings.json"),
        "{\"responses\":{\"identity\":{\"response\":" + answer + "}}}");
  }
}
