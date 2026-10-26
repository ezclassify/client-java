package com.ezclassify.rest.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenScriptTest {

  @Test
  void mavenLifecycleInstallsPinnedGeneratorDependenciesBeforeGeneration() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    String pom = Files.readString(moduleDir.resolve("pom.xml"));

    int install = pom.indexOf("<id>install-java-regen-tools</id>");
    int generate = pom.indexOf("<id>generate-java-model</id>");
    assertTrue(install >= 0, "Maven must install the lockfile-pinned generator toolchain");
    assertTrue(generate > install, "toolchain installation must precede model generation");
    assertTrue(
        pom.substring(install, generate).contains("<phase>initialize</phase>"),
        "generator dependencies must be installed during Maven initialization");
    assertTrue(
        pom.substring(install, generate).contains("<argument>ci</argument>"),
        "Maven must use npm ci so package-lock.json remains authoritative");
  }

  @Test
  void generatedGuideScanResultDoesNotKeepRawLinkTags() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path script = moduleDir.resolve("scripts/gen.mjs");
    // The committed vendor schemas are the generator's real input.
    Path schemasDir = moduleDir.resolveSibling("vendor/spec/json-schemas");
    Path outputDir = Files.createTempDirectory("java-types-gen");
    Path openapi = moduleDir.resolveSibling("vendor/spec/openapi.yaml");

    Process process =
        new ProcessBuilder(
                List.of(
                    "node",
                    script.toString(),
                    schemasDir.toString(),
                    outputDir.toString(),
                    "com.ezclassify.rest.contract",
                    openapi.toString()))
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();

    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    assertEquals(0, exitCode, () -> "generator failed:\n" + output);

    String generated = Files.readString(outputDir.resolve("GuideScanResult.java"));
    assertFalse(generated.contains("{@link"), "generated Javadoc should not contain raw link tags");
  }

  @Test
  void generatedRecordsDocumentEveryComponentFromTheContractDescriptions() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path outputDir = Files.createTempDirectory("java-types-javadoc");
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                outputDir.toString(),
                "com.ezclassify.rest.contract",
                moduleDir.resolveSibling("vendor/spec/openapi.yaml").toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), () -> "generator failed:\n" + output);

    // A component the schema describes carries that text, with markdown code spans as {@code}.
    String categoryAccess = Files.readString(outputDir.resolve("CategoryAccessResult.java"));
    assertTrue(categoryAccess.contains("@param missingDisplay Resolved operational display"));
    assertTrue(categoryAccess.contains("{@code missing}"), categoryAccess);
    assertFalse(categoryAccess.contains("`"), categoryAccess);
    // A component the schema leaves undescribed still gets a tag, so javadoc stays complete.
    assertTrue(categoryAccess.contains("@param granted "), categoryAccess);
    // Nested records document their components as well.
    String guideView = Files.readString(outputDir.resolve("GuideViewResponse.java"));
    assertTrue(guideView.contains("@param label "), guideView);
    // Schema text never reaches the comment as raw markup.
    String audit = Files.readString(outputDir.resolve("CtxDocumentAuditRequestBody.java"));
    assertTrue(audit.contains("&#123;ctxId&#125;"), audit);
  }

  @Test
  void generatedSchemaCatalogAvoidsHandwrittenCollisionsAndResolvesLocalEnums() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path outputDir = Files.createTempDirectory("java-types-catalog");
    Files.writeString(outputDir.resolve("EngineResult.java"), "stale generated source");
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                outputDir.toString(),
                "com.ezclassify.rest.contract",
                moduleDir.resolveSibling("vendor/spec/openapi.yaml").toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), () -> "generator failed:\n" + output);

    // A type the contract no longer declares does not survive a run.
    assertFalse(Files.exists(outputDir.resolve("EngineResult.java")));
    // Records with no handwritten seam come from the reviewed OpenAPI graph, in its field order.
    String suggestResult = Files.readString(outputDir.resolve("SuggestResult.java"));
    assertTrue(
        suggestResult.contains(
            "java.util.List<Suggestion> suggestions,\n    java.util.List<Warning> warnings\n"),
        suggestResult);
    String explanationTrace = Files.readString(outputDir.resolve("ExplanationTrace.java"));
    assertTrue(
        explanationTrace.contains(
            "java.util.List<TraceValidationIssue> validationErrors,\n"
                + "    java.util.List<TraceInference> inferences,"),
        explanationTrace);
    assertTrue(explanationTrace.contains("java.util.List<TraceEntry> entries"), explanationTrace);
    String categoryAccess = Files.readString(outputDir.resolve("CategoryAccessResult.java"));
    assertTrue(categoryAccess.contains("AccessRule rule"));
    String accessRule = Files.readString(outputDir.resolve("AccessRule.java"));
    assertTrue(accessRule.contains("GTE = new AccessRule(\"gte\""));
    assertTrue(accessRule.contains("SUPERSET = new AccessRule(\"superset\""));
    assertTrue(accessRule.contains("INCLUDES = new AccessRule(\"includes\""));
    assertTrue(accessRule.contains("EXACT = new AccessRule(\"exact\""));
    assertTrue(accessRule.contains("ANY = new AccessRule(\"any\""));
    assertTrue(
        accessRule.contains(
            "public final class AccessRule extends ExpandableStringEnum<AccessRule.Value>"));
    assertTrue(accessRule.contains("static AccessRule fromWire(String token)"));
    assertTrue(accessRule.contains("static AccessRule fromString(String token)"));
    assertFalse(Files.exists(outputDir.resolve("openapi")));

    String sourceReference = Files.readString(outputDir.resolve("SourceReference.java"));
    assertTrue(sourceReference.contains("Reproducibility reproducibility"));
    assertTrue(sourceReference.contains("String snapshotId"));

    String compilationStatus = Files.readString(outputDir.resolve("GuideCompilationStatus.java"));
    assertTrue(
        compilationStatus.contains(
            "PENDING_DETERMINATION = new GuideCompilationStatus(\"pending-determination\""));
    assertTrue(
        compilationStatus.contains("NO_FINDINGS = new GuideCompilationStatus(\"no-findings\""));
    assertTrue(
        compilationStatus.contains(
            "DETERMINED_SUFFICIENT = new GuideCompilationStatus(\"determined-sufficient\""));
    assertTrue(
        compilationStatus.contains(
            "DETERMINED_INSUFFICIENT = new GuideCompilationStatus(\"determined-insufficient\""));

    String evaluation = Files.readString(outputDir.resolve("EvaluationResult.java"));
    assertTrue(evaluation.contains("java.util.List<GuideRule> selectedRules"));
    String guideRule = Files.readString(outputDir.resolve("GuideRule.java"));
    assertTrue(guideRule.contains("String id"));
    assertTrue(guideRule.contains("GuideRuleOutcome outcome"));

    assertTrue(
        Files.readString(outputDir.resolve("Assertion.java"))
            .contains("AssertionConfidence confidence"));
    assertTrue(
        Files.readString(outputDir.resolve("ValidationError.java"))
            .contains("ValidationSeverity severity"));
    assertTrue(
        Files.readString(outputDir.resolve("AccessScenarioAssessment.java"))
            .contains("AccessScenarioBasis basis"));
    assertTrue(
        Files.readString(outputDir.resolve("AccessScenarioAssessment.java"))
            .contains("EffectiveDocumentTarget target"));
    assertTrue(
        Files.readString(outputDir.resolve("DocumentAuditReport.java")).contains("String whole"));
    assertTrue(
        Files.readString(outputDir.resolve("DocumentAccessAssessment.java"))
            .contains("AccessScenarioAssessment projected"));
    assertTrue(Files.readString(outputDir.resolve("RuleCondition.java")).contains("Object value"));
    assertTrue(
        Files.readString(outputDir.resolve("TraceRepair.java"))
            .contains("RepairConfidence confidence"));
    assertTrue(
        Files.readString(outputDir.resolve("Fix.java"))
            .contains("java.util.List<FixConceptReference> keeps"));
    assertTrue(Files.readString(outputDir.resolve("Change.java")).contains("FixAction action"));
    assertTrue(
        Files.readString(outputDir.resolve("MatchContextNode.java"))
            .contains("MatchContextExpression context"));
    String schemeScope = Files.readString(outputDir.resolve("SchemeScope.java"));
    assertTrue(schemeScope.contains("String id"), schemeScope);
    assertTrue(schemeScope.contains("String label"), schemeScope);
    assertFalse(schemeScope.contains("defaultValue"), schemeScope);
    assertFalse(schemeScope.contains("JSON_FIELD_DEFAULT"), schemeScope);

    String crosswalkOutcome = Files.readString(outputDir.resolve("CrosswalkOutcome.java"));
    assertTrue(crosswalkOutcome.contains("Disposition disposition"));
    assertTrue(crosswalkOutcome.contains("CARRY = new Disposition(\"carry\""));
    assertTrue(crosswalkOutcome.contains("REVIEW = new Disposition(\"review\""));
    assertTrue(crosswalkOutcome.contains("OMIT = new Disposition(\"omit\""));
    assertTrue(crosswalkOutcome.contains("FORBID = new Disposition(\"forbid\""));
    assertTrue(crosswalkOutcome.contains("UNMAPPED = new Disposition(\"unmapped\""));
    assertTrue(
        crosswalkOutcome.contains(
            "public static final class Disposition extends ExpandableStringEnum<Disposition.Value>"));
    assertTrue(crosswalkOutcome.contains("static Disposition fromWire(String token)"));
    String crosswalkTranslation =
        Files.readString(outputDir.resolve("CrosswalkTranslationResult.java"));
    assertTrue(crosswalkTranslation.contains("ArtifactReference crosswalk"));
    assertTrue(crosswalkTranslation.contains("ValidateResult source"));
    assertTrue(crosswalkTranslation.contains("ValidateResult target"));
    // Each variant tags itself with its own literal status, which stays a String.
    assertTrue(crosswalkTranslation.contains("String status"));
    assertTrue(crosswalkTranslation.contains("forbiddenBy"));
    assertFalse(Files.exists(outputDir.resolve("AuditVerdict.java")));
    assertFalse(Files.exists(outputDir.resolve("Confidence.java")));
    assertFalse(Files.exists(outputDir.resolve("EffectiveTargetBasis.java")));
    assertFalse(Files.exists(outputDir.resolve("GuideDecision.java")));
    assertFalse(Files.exists(outputDir.resolve("Severity.java")));
    try (var files = Files.list(outputDir)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().contains("<")));
    }
    // Each variant of the union names its own enum for `reason`, so the carrier's enum holds both
    // vocabularies: a fact's reason and an output's reason both decode as declared values. Each
    // variant's enum is still generated on its own.
    String unrepresentable = Files.readString(outputDir.resolve("UnrepresentableValue.java"));
    assertTrue(unrepresentable.contains("Reason reason"), unrepresentable);
    assertTrue(unrepresentable.contains("String conceptId"), unrepresentable);
    assertTrue(unrepresentable.contains("String output"), unrepresentable);
    assertTrue(unrepresentable.contains("NO_FIELD = new Reason(\"no-field\""), unrepresentable);
    assertTrue(
        unrepresentable.contains(
            "UNRESOLVED_CONTEXT = new Reason(\"unresolved-context\", Value.UNRESOLVED_CONTEXT)"),
        unrepresentable);
    assertTrue(Files.exists(outputDir.resolve("UnrepresentableFactReason.java")));
    assertTrue(Files.exists(outputDir.resolve("UnrepresentableOutputReason.java")));
  }

  @Test
  void schemeResourceViewsAreGeneratedWithUnionsReadAsTheContractSpellsThem() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path outputDir = Files.createTempDirectory("java-scheme-resources");
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                outputDir.toString(),
                "com.ezclassify.rest.contract",
                moduleDir.resolveSibling("vendor/spec/openapi.yaml").toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), () -> "generator failed:\n" + output);

    for (String view :
        new String[] {
          "CategoryView", "FormatView", "LabelPresetView", "SchemeGrammar", "SchemeSummary"
        }) {
      assertTrue(Files.exists(outputDir.resolve(view + ".java")), view);
    }
    // A union of string constants is a closed string enum, so it is as open to a newer server as
    // every other contract enum.
    String syntaxType = Files.readString(outputDir.resolve("FormatSyntaxType.java"));
    assertTrue(
        syntaxType.contains("extends ExpandableStringEnum<FormatSyntaxType.Value>"), syntaxType);
    assertTrue(syntaxType.contains("\"xml-attributes\""), syntaxType);
    // The two fields the contract spells as a scalar-or-structure union stay opaque values.
    String category = Files.readString(outputDir.resolve("CategoryView.java"));
    assertTrue(category.contains("Object sort,"), category);
    assertTrue(category.contains("Object defaultConcept"), category);
    // What an opaque field refers to is not part of the Java API, so nothing is published for it.
    assertFalse(Files.exists(outputDir.resolve("SortType.java")));
    assertFalse(Files.exists(outputDir.resolve("ConditionalDefaultConcept.java")));
  }

  @Test
  void ambiguousPublicUnionStillFailsGeneration() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path openapi = moduleDir.resolveSibling("vendor/spec/openapi.yaml");
    String source = Files.readString(openapi);
    String nullableTarget =
        "        target:\n"
            + "          anyOf:\n"
            + "            - $ref: \"#/components/schemas/EffectiveDocumentTarget\"\n"
            + "            - type: \"null\"\n";
    assertTrue(source.contains(nullableTarget), "the reviewed nullable target must be present");
    Path ambiguousOpenapi = Files.createTempFile("java-ambiguous-openapi", ".yaml");
    Files.writeString(
        ambiguousOpenapi,
        source.replace(nullableTarget, nullableTarget + "            - type: boolean\n"));
    Path outputDir = Files.createTempDirectory("java-ambiguous-union-output");

    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                outputDir.toString(),
                "com.ezclassify.rest.contract",
                ambiguousOpenapi.toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertFalse(process.waitFor() == 0, "ambiguous public union must be rejected");
    assertTrue(output.contains("unsupported public union at AccessScenarioAssessment.target"));
  }

  @Test
  void aWireValueCollidingWithAMemberEveryContractEnumDeclaresFailsGeneration() throws Exception {
    // Every generated enum declares Value.UNKNOWN and the BY_TOKEN and IN_ORDER indexes, so a
    // token that would become one of those names must stop generation instead of miscompiling.
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    String source = Files.readString(moduleDir.resolveSibling("vendor/spec/openapi.yaml"));
    String scanStatus = "        - truncated\n        - resource-limit\n";
    assertTrue(source.contains(scanStatus), "the reviewed ScanStatus values must be present");
    for (String token : List.of("unknown", "by-token", "in-order")) {
      Path openapi = Files.createTempFile("java-reserved-enum-openapi", ".yaml");
      Files.writeString(
          openapi, source.replace(scanStatus, scanStatus + "        - " + token + "\n"));
      Process process =
          new ProcessBuilder(
                  "node",
                  moduleDir.resolve("scripts/gen.mjs").toString(),
                  moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                  Files.createTempDirectory("java-reserved-enum-output").toString(),
                  "com.ezclassify.rest.contract",
                  openapi.toString())
              .directory(moduleDir.toFile())
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(process.waitFor() == 0, token + " must be rejected");
      assertTrue(output.contains("ScanStatus declares the wire value \"" + token + "\""), output);
    }
  }

  @Test
  void twoWireValuesThatBecomeOneConstantFailGeneration() throws Exception {
    // resource-limit and resourceLimit both become RESOURCE_LIMIT, which would not compile.
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    String source = Files.readString(moduleDir.resolveSibling("vendor/spec/openapi.yaml"));
    String scanStatus = "        - truncated\n        - resource-limit\n";
    assertTrue(source.contains(scanStatus), "the reviewed ScanStatus values must be present");
    Path openapi = Files.createTempFile("java-colliding-enum-openapi", ".yaml");
    Files.writeString(
        openapi, source.replace(scanStatus, scanStatus + "        - resourceLimit\n"));
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                Files.createTempDirectory("java-colliding-enum-output").toString(),
                "com.ezclassify.rest.contract",
                openapi.toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertFalse(process.waitFor() == 0, "two values sharing one constant must be rejected");
    assertTrue(
        output.contains(
            "ScanStatus declares the wire values \"resource-limit\" and \"resourceLimit\", which"
                + " both become the constant RESOURCE_LIMIT"),
        output);
  }

  @Test
  void recursiveDictionaryAliasFallsBackToOpaqueValuesWithoutOverflowing() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path schemasDir = Files.createTempDirectory("java-recursive-map-schemas");
    try (var files = Files.list(moduleDir.resolveSibling("vendor/spec/json-schemas"))) {
      for (Path source :
          files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
        Files.copy(source, schemasDir.resolve(source.getFileName()));
      }
    }
    Files.writeString(
        schemasDir.resolve("recursive-map.schema.json"),
        """
        {
          "type": "object",
          "properties": { "children": { "$ref": "#/definitions/RecursiveMap" } },
          "required": ["children"],
          "definitions": {
            "RecursiveMap": {
              "type": "object",
              "additionalProperties": { "$ref": "#/definitions/RecursiveMap" }
            }
          }
        }
        """);
    Path outputDir = Files.createTempDirectory("java-recursive-map-output");
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                schemasDir.toString(),
                outputDir.toString(),
                "com.ezclassify.rest.contract",
                moduleDir.resolveSibling("vendor/spec/openapi.yaml").toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), () -> "generator failed:\n" + output);

    String generated = Files.readString(outputDir.resolve("RecursiveMap.java"));
    assertTrue(generated.contains("java.util.Map<String, Object> children"));
  }

  @Test
  void aUnionPropertyTheVariantsDeclareAsDifferentTypesFailsGeneration() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    String source = Files.readString(moduleDir.resolveSibling("vendor/spec/openapi.yaml"));
    String outputDetail =
        "        detail:\n"
            + "          type: string\n"
            + "          description: Human-readable detail, for a message rather than a branch.\n"
            + "      required:\n"
            + "        - output\n";
    assertTrue(source.contains(outputDetail), "the reviewed UnrepresentableOutput must be present");
    Path openapi = Files.createTempFile("java-incompatible-union-openapi", ".yaml");
    Files.writeString(
        openapi,
        source.replace(outputDetail, outputDetail.replace("type: string", "type: integer")));
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                moduleDir.resolveSibling("vendor/spec/json-schemas").toString(),
                Files.createTempDirectory("java-incompatible-union-output").toString(),
                "com.ezclassify.rest.contract",
                openapi.toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertFalse(process.waitFor() == 0, "a field with no single Java type must be rejected");
    assertTrue(
        output.contains("incompatible public union property at UnrepresentableValue.detail"),
        output);
  }

  @Test
  void aSchemaUnionWithAVariantThatIsNotAnObjectFailsGeneration() throws Exception {
    Path moduleDir = Path.of(System.getProperty("user.dir"));
    Path schemasDir = Files.createTempDirectory("java-mixed-union-schemas");
    try (var files = Files.list(moduleDir.resolveSibling("vendor/spec/json-schemas"))) {
      for (Path source :
          files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
        Files.copy(source, schemasDir.resolve(source.getFileName()));
      }
    }
    Files.writeString(
        schemasDir.resolve("mixed-holder.schema.json"),
        """
        {
          "type": "object",
          "properties": { "held": { "$ref": "#/definitions/MixedUnion" } },
          "required": ["held"],
          "definitions": {
            "MixedUnion": {
              "anyOf": [
                { "type": "string" },
                { "type": "object", "properties": { "name": { "type": "string" } } }
              ]
            }
          }
        }
        """);
    Process process =
        new ProcessBuilder(
                "node",
                moduleDir.resolve("scripts/gen.mjs").toString(),
                schemasDir.toString(),
                Files.createTempDirectory("java-mixed-union-output").toString(),
                "com.ezclassify.rest.contract",
                moduleDir.resolveSibling("vendor/spec/openapi.yaml").toString())
            .directory(moduleDir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertFalse(process.waitFor() == 0, "a union with a non-object variant must be rejected");
    assertTrue(
        output.contains("record union requires object variants with named properties"), output);
  }
}
