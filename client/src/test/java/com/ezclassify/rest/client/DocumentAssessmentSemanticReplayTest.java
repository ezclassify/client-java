package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.ContextAccessRequirementsResult;
import com.ezclassify.rest.contract.ContextDocumentAccessAssessmentResult;
import com.ezclassify.rest.contract.ContextDocumentAuditResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Replays every vendored document-assessment semantic case against its public wire DTO. */
class DocumentAssessmentSemanticReplayTest {

  private static final Pattern SHA1_HEX = Pattern.compile("^[0-9a-f]{40}$");
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern SEMVER_PREFIX = Pattern.compile("^\\d+\\.\\d+\\..*");
  private static final Pattern OPENAPI_INFO_VERSION =
      Pattern.compile("^ {2}version:\\s+(\\S+)$", Pattern.MULTILINE);

  private static final Path SEMANTIC =
      Path.of(System.getProperty("user.dir")).resolve("..").resolve("vendor/spec/semantic/v1");
  private static final Path VENDOR = SEMANTIC.getParent().getParent();

  @Test
  void replaysEveryContractCaseAndPublicDtoShapes() throws Exception {
    JsonObject contract = object(SEMANTIC.resolve("document-assessment.contract.json"));
    JsonObject requests = object(SEMANTIC.resolve("fixtures/requests.json"));
    JsonObject recordings = object(SEMANTIC.resolve("fixtures/recordings.json"));
    JsonArray cases = contract.getAsJsonArray("cases");
    assertCorpusIsComplete(cases, requests, recordings.getAsJsonObject("cases"));

    for (JsonElement item : cases) {
      JsonObject testCase = item.getAsJsonObject();
      String requestId = string(testCase, "requestId");
      assertTrue(requests.has(requestId), () -> "missing request " + requestId);
      JsonObject recording = recordings.getAsJsonObject("cases").getAsJsonObject(requestId);
      assertNotNull(recording, () -> "missing recording " + requestId);
      JsonElement output = recording.get("output");
      assertNotNull(output, () -> "missing output " + requestId);

      // Deserialize the complete documented success envelope through the public DTO, then write it
      // back through the same public mapper. Error cases intentionally retain their HTTP body
      // shape.
      if (output.isJsonObject() && output.getAsJsonObject().has("result")) {
        roundTripPublicEnvelope(testCase, output);
      }
      for (JsonElement assertion : testCase.getAsJsonArray("assertions")) {
        assertAssertion(output, assertion.getAsJsonObject(), requestId);
      }
    }
  }

  /**
   * The corpus is the count. Every contract case is numbered in order from 1 and has exactly one
   * request and one recording, and nothing in either file lacks a case, so a dropped or added case
   * shows up here without a pinned total to move.
   */
  private static void assertCorpusIsComplete(
      JsonArray cases, JsonObject requests, JsonObject recordings) {
    assertTrue(!cases.isEmpty(), "the semantic contract declares no cases");
    List<String> requestIds = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      JsonObject testCase = cases.get(i).getAsJsonObject();
      assertEquals(i + 1, testCase.get("number").getAsInt(), "semantic case numbering has a gap");
      requestIds.add(string(testCase, "requestId"));
    }
    List<String> sortedIds = new ArrayList<>(requestIds);
    sortedIds.sort(String::compareTo);
    List<String> requestKeys = new ArrayList<>(requests.keySet());
    requestKeys.sort(String::compareTo);
    List<String> recordingKeys = new ArrayList<>(recordings.keySet());
    recordingKeys.sort(String::compareTo);
    assertEquals(sortedIds, requestKeys, "requests.json and the contract name different cases");
    assertEquals(sortedIds, recordingKeys, "recordings.json and the contract name different cases");
  }

  /**
   * Checks what the vendored bundle claims about where it came from, and whether the claim is true.
   *
   * <p>These are assertions about SHAPE and INTERNAL CONSISTENCY, never about one blessed commit.
   * `specDigest` is computed upstream over the spec bot's own bytes, so a change to the bot
   * re-stales every mirror without a single spec byte moving. A literal digest pinned here would go
   * red for a reason that has nothing to do with this SDK, and the bot that invalidated it writes
   * only `vendor/`, so it could not repair the pin either. Everything below survives a legitimate
   * re-vendor and only goes red when the provenance is actually wrong.
   */
  @Test
  void rejectsAnySemanticBundleOrGeneratorProvenanceDrift() throws Exception {
    JsonObject manifest = object(VENDOR.resolve("MANIFEST.json"));
    // The source is a contracts commit, and SPEC.md has to tell the same story: the two files are
    // written in one pass, so a disagreement means the writer half-applied a change.
    String contractsSha = string(manifest, "contractsSha");
    assertMatches(SHA1_HEX, contractsSha, "MANIFEST.json contractsSha");
    String specMarkdown = Files.readString(VENDOR.resolveSibling("SPEC.md"));
    assertEquals(
        contractsSha,
        provenanceRow(specMarkdown, "contracts@"),
        "SPEC.md and MANIFEST.json disagree about the source contracts commit");
    String specSourceUrl = provenanceRow(specMarkdown, "specSourceUrl");
    assertNotNull(specSourceUrl, "SPEC.md is missing its specSourceUrl provenance row");
    assertTrue(
        specSourceUrl.contains("github.com/ezclassify/contracts")
            && specSourceUrl.contains(contractsSha),
        () -> "specSourceUrl does not point at contracts@" + contractsSha + ": " + specSourceUrl);

    // The recorded openapiVersion has to be the version the vendored document itself declares.
    String openapiYaml = Files.readString(VENDOR.resolve("openapi.yaml"));
    var declaredVersion = OPENAPI_INFO_VERSION.matcher(openapiYaml);
    assertTrue(declaredVersion.find(), "vendor/spec/openapi.yaml declares no info.version");
    assertEquals(
        declaredVersion.group(1),
        string(manifest, "openapiVersion"),
        "MANIFEST.json openapiVersion disagrees with the vendored openapi.yaml");

    JsonObject sourcePackages = manifest.getAsJsonObject("sourcePackages");
    assertNotNull(sourcePackages, "MANIFEST.json is missing the sourcePackages provenance block");
    assertTrue(
        !sourcePackages.keySet().isEmpty(), "MANIFEST.json sourcePackages block names no packages");
    for (Map.Entry<String, JsonElement> entry : sourcePackages.entrySet()) {
      assertTrue(
          entry.getKey().startsWith("@ezclassify/"),
          () -> "sourcePackages names a foreign package: " + entry.getKey());
      assertMatches(
          SEMVER_PREFIX, entry.getValue().getAsString(), "sourcePackages " + entry.getKey());
    }

    // Content hashes have to be hashes, and the one we can recompute has to be the real one.
    assertMatches(SHA256_HEX, string(manifest, "specDigest"), "MANIFEST.json specDigest");
    assertMatches(
        SHA256_HEX, string(manifest, "specBundleSha256"), "MANIFEST.json specBundleSha256");
    assertEquals(
        sha256(VENDOR.resolve("openapi.yaml")),
        string(manifest, "openapiSha256"),
        "MANIFEST.json openapiSha256 is not the hash of the openapi.yaml that is committed");

    // The generator this SDK actually runs, and every input the manifest pins alongside it.
    JsonObject javaGenerator = manifest.getAsJsonObject("generators").getAsJsonObject("java");
    assertNotNull(javaGenerator, "MANIFEST.json records no java generator");
    Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
    assertEquals(
        sha256(repoRoot.resolve(string(javaGenerator, "script"))),
        string(javaGenerator, "sha256"),
        "MANIFEST.json pins a different generator than the one in the tree");
    for (JsonElement input : javaGenerator.getAsJsonArray("additionalInputs")) {
      JsonObject additional = input.getAsJsonObject();
      String relativePath = string(additional, "path");
      assertEquals(
          sha256(repoRoot.resolve(relativePath)),
          string(additional, "sha256"),
          "MANIFEST.json pins a stale hash for generator input " + relativePath);
    }

    List<String> expected =
        List.of(
            "document-assessment.contract.json",
            "document-assessment.schema.json",
            "fixtures/clause-coverage.json",
            "fixtures/clause-coverage.schema.json",
            "fixtures/context.json",
            "fixtures/context.schema.json",
            "fixtures/recordings.json",
            "fixtures/recordings.schema.json",
            "fixtures/requests.json",
            "fixtures/requests.schema.json");
    List<String> actual = new ArrayList<>();
    try (var files = Files.walk(SEMANTIC)) {
      files
          .filter(Files::isRegularFile)
          .forEach(path -> actual.add(SEMANTIC.relativize(path).toString()));
    }
    actual.sort(String::compareTo);
    assertEquals(expected, actual, "semantic fixture inventory changed");
  }

  @Test
  void replaysEveryRecordedOperationThroughTheContextHttpTransport() throws Exception {
    JsonObject requests = object(SEMANTIC.resolve("fixtures/requests.json"));
    JsonObject recordings =
        object(SEMANTIC.resolve("fixtures/recordings.json")).getAsJsonObject("cases");
    JsonObject fixtureContext = object(SEMANTIC.resolve("fixtures/context.json"));
    List<String> captured = new ArrayList<>();
    JsonArray cases =
        object(SEMANTIC.resolve("document-assessment.contract.json")).getAsJsonArray("cases");
    Deque<JsonObject> outputs = new ArrayDeque<>();
    for (JsonElement item : cases) {
      outputs.add(recordings.getAsJsonObject(string(item.getAsJsonObject(), "requestId")));
    }
    List<String> expected = new ArrayList<>();
    try (ReplayServer server = new ReplayServer(outputs, captured)) {
      Map<ContextKey, EzClassifyContext> contexts = new java.util.HashMap<>();
      for (JsonElement item : cases) {
        JsonObject testCase = item.getAsJsonObject();
        String requestId = string(testCase, "requestId");
        JsonObject request = requests.getAsJsonObject(requestId);
        JsonObject output = recordings.getAsJsonObject(requestId).getAsJsonObject("output");
        String operation = string(testCase, "operation");
        String route = route(operation);
        // A case that names a fixture variant runs against the Context that variant's scheme
        // fixture declares, the way the recorder ran it.
        ContextKey key = contextFor(fixtureContext, request, requestId);
        assertVariantMatchesRecording(request, output, key, requestId);
        EzClassifyContext context =
            contexts.computeIfAbsent(
                key, k -> EzClassifyClient.connect(server.url(), k.id(), k.version()));
        expected.add(key.path() + route + "\n" + request);
        if (output.has("error")) {
          JsonObject expectedError = output.getAsJsonObject("error");
          EzClassifyClientException error =
              org.junit.jupiter.api.Assertions.assertThrows(
                  EzClassifyClientException.class, () -> context.postJson(route, request));
          assertEquals(string(expectedError, "message"), error.getMessage());
          assertEquals(string(expectedError, "code"), error.code());
          assertEquals(string(expectedError, "detail"), error.detail());
          continue;
        }
        JsonObject received = context.postJson(route, request);
        assertEquals(output, received, requestId + " response changed");
        assertConcreteDto(operation, received, requestId);
      }
      assertEquals(cases.size(), captured.size(), "one request per contract case");
      for (int i = 0; i < cases.size(); i++) {
        assertEquals(
            expected.get(i),
            captured.get(i),
            string(cases.get(i).getAsJsonObject(), "requestId") + " request changed");
      }
    }
  }

  /** The Context a replayed request is sent to. */
  private record ContextKey(String id, String version) {
    String path() {
      return id + "/" + version + "/";
    }
  }

  /**
   * Resolves the Context a semantic request runs against from the published variant map. The base
   * fixtures apply unless the request names a {@code fixtureVariant}, whose scheme fixture then
   * replaces the base one. The Context's identity is the one that scheme fixture declares.
   */
  private static ContextKey contextFor(JsonObject fixtureContext, JsonObject request, String id)
      throws Exception {
    String schemeFixture = string(fixtureContext, "schemeFixture");
    JsonElement variantName = request.get("fixtureVariant");
    if (variantName != null) {
      JsonObject variant =
          fixtureContext.getAsJsonObject("variants").getAsJsonObject(variantName.getAsString());
      assertNotNull(variant, () -> id + " names an undeclared fixture variant " + variantName);
      if (variant.has("schemeFixture")) schemeFixture = string(variant, "schemeFixture");
    }
    Path fixture = SEMANTIC.resolve("fixtures").resolve(schemeFixture).normalize();
    JsonObject scheme = object(fixture).getAsJsonObject("scheme");
    assertNotNull(scheme, () -> fixture + " declares no scheme");
    return new ContextKey(string(scheme, "id"), string(scheme, "version"));
  }

  /**
   * A variant case's recording names the Context it was recorded against. Holding the two equal
   * proves the runner read the variant map the way the recorder did.
   */
  private static void assertVariantMatchesRecording(
      JsonObject request, JsonObject output, ContextKey key, String requestId) {
    if (!request.has("fixtureVariant") || !output.has("resolution")) return;
    JsonElement recorded = output.getAsJsonObject("resolution").get("contextId");
    if (recorded == null) return;
    assertEquals(recorded.getAsString(), key.id(), requestId + " ran against a different Context");
  }

  private static String route(String operation) {
    return switch (operation) {
      case "auditDocument", "cliDocument" -> "document/audit";
      case "assessDocumentAccess" -> "document/assess-access";
      case "documentAccessRequirements" -> "access/describe-requirements";
      default -> throw new AssertionError("unsupported semantic operation: " + operation);
    };
  }

  private static void assertConcreteDto(String operation, JsonObject output, String requestId) {
    Object dto =
        switch (operation) {
          case "auditDocument", "cliDocument" ->
              WireJson.fromTree(output, ContextDocumentAuditResult.class);
          case "assessDocumentAccess" ->
              WireJson.fromTree(output, ContextDocumentAccessAssessmentResult.class);
          case "documentAccessRequirements" ->
              WireJson.fromTree(output, ContextAccessRequirementsResult.class);
          default -> throw new AssertionError("unsupported semantic operation: " + operation);
        };
    assertEquals(
        withoutNullMembers(output),
        withoutNullMembers(WireJson.toTree(dto)),
        requestId + " DTO lost or changed a non-null wire field");
  }

  private static JsonElement withoutNullMembers(JsonElement value) {
    if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value;
    if (value.isJsonArray()) {
      JsonArray array = new JsonArray();
      for (JsonElement item : value.getAsJsonArray()) array.add(withoutNullMembers(item));
      return array;
    }
    JsonObject object = new JsonObject();
    for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
      if (!entry.getValue().isJsonNull()) {
        object.add(entry.getKey(), withoutNullMembers(entry.getValue()));
      }
    }
    return object;
  }

  @Test
  void publicHttpErrorAdapterPreservesErrorCodeAndDetail() throws Exception {
    try (ReplayServer server = ReplayServer.error()) {
      EzClassifyContext context = EzClassifyClient.connect(server.url(), "ref-scheme-1", "1.0.0");
      EzClassifyClientException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              EzClassifyClientException.class, () -> context.document().audit("input"));
      assertEquals("stale recording", error.getMessage());
      assertEquals("STALE_REFERENCE", error.code());
      assertEquals("finding", error.detail());
    }
  }

  @Test
  void replayServerPreservesUtf8RequestAndResponsePayloads() throws Exception {
    JsonObject request = Json.parseObject("{\"text\":\"café\"}");
    // The smallest audit answer the contract allows, carrying non-ASCII text both ways.
    JsonObject expected =
        Json.parseObject(
            "{\"resolution\":{\"contextId\":\"ref-scheme-1\",\"resolvedVersion\":\"1.0.0\","
                + "\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],"
                + "\"result\":{\"markingRequirements\":{\"whole\":true,\"part\":true},"
                + "\"whole\":null,\"wholes\":[],\"wholeValidation\":null,\"parts\":[],"
                + "\"wholeCoversParts\":null,\"unmarkedSpans\":[],"
                + "\"proceduralFlags\":[\"alias → concept\"],\"overallVerdict\":\"needs-review\","
                + "\"vocabulary\":null,\"documentDigest\":\"sha256:café\","
                + "\"guideAssessment\":null,\"limitations\":[]}}");
    JsonObject recording = new JsonObject();
    recording.add("output", expected);
    List<String> captured = new ArrayList<>();
    Deque<JsonObject> recordings = new ArrayDeque<>();
    recordings.add(recording);

    try (ReplayServer server = new ReplayServer(recordings, captured)) {
      EzClassifyContext context = EzClassifyClient.connect(server.url(), "ref-scheme-1", "1.0.0");
      assertEquals(expected, context.postJson("document/audit", request));
    }

    assertEquals(List.of("ref-scheme-1/1.0.0/document/audit\n" + request), captured);
  }

  private static void roundTripPublicEnvelope(JsonObject testCase, JsonElement output) {
    String operation = string(testCase, "operation");
    if ("assessDocumentAccess".equals(operation)) {
      ContextDocumentAccessAssessmentResult dto =
          WireJson.fromTree(output, ContextDocumentAccessAssessmentResult.class);
      assertNotNull(dto.resolution());
      assertNotNull(WireJson.toTree(dto));
    } else {
      ContextDocumentAuditResult dto = WireJson.fromTree(output, ContextDocumentAuditResult.class);
      assertNotNull(dto.resolution());
      assertNotNull(WireJson.toTree(dto));
    }
  }

  private static void assertAssertion(JsonElement root, JsonObject assertion, String requestId) {
    JsonElement value = path(root, string(assertion, "path"));
    switch (string(assertion, "operator")) {
      case "equals" -> assertEquals(assertion.get("value"), value, requestId + " " + assertion);
      case "present" -> assertNotNull(value, requestId + " " + assertion);
      case "absent" -> assertTrue(value == null, requestId + " " + assertion);
      case "minItems" -> {
        assertNotNull(value, requestId + " " + assertion);
        assertTrue(value.isJsonArray(), requestId + " expected array " + assertion);
        assertTrue(
            value.getAsJsonArray().size() >= assertion.get("value").getAsInt(),
            requestId + " " + assertion);
      }
      case "contains" -> {
        assertNotNull(value, requestId + " " + assertion);
        JsonElement expected = assertion.get("value");
        boolean found = false;
        if (value.isJsonArray()) {
          for (JsonElement candidate : value.getAsJsonArray()) {
            if (candidate.equals(expected)) {
              found = true;
              break;
            }
          }
        } else if (value.isJsonPrimitive()) {
          found = value.getAsString().contains(expected.getAsString());
        }
        assertTrue(found, requestId + " " + assertion);
      }
      default ->
          throw new AssertionError(
              "unsupported semantic operator: " + string(assertion, "operator"));
    }
  }

  private static JsonElement path(JsonElement root, String dottedPath) {
    return path(root, dottedPath.split("\\."), 0);
  }

  private static JsonElement path(JsonElement current, String[] parts, int position) {
    if (position == parts.length) return current;
    String part = parts[position];
    if ("*".equals(part)) {
      if (current == null || !current.isJsonArray()) return null;
      JsonArray values = new JsonArray();
      for (JsonElement item : current.getAsJsonArray()) {
        JsonElement resolved = path(item, parts, position + 1);
        if (resolved != null) values.add(resolved);
      }
      return values.isEmpty() ? null : values;
    }
    if (current == null || !current.isJsonObject()) return null;
    int bracket = part.indexOf('[');
    String property = bracket < 0 ? part : part.substring(0, bracket);
    current = current.getAsJsonObject().get(property);
    if (bracket >= 0) {
      int end = part.indexOf(']', bracket);
      if (end < 0 || current == null || !current.isJsonArray()) return null;
      String selector = part.substring(bracket + 1, end);
      if ("*".equals(selector)) {
        JsonArray values = new JsonArray();
        for (JsonElement item : current.getAsJsonArray()) {
          JsonElement resolved = path(item, parts, position + 1);
          if (resolved != null) values.add(resolved);
        }
        return values.isEmpty() ? null : values;
      }
      int index = Integer.parseInt(selector);
      if (index >= current.getAsJsonArray().size()) return null;
      current = current.getAsJsonArray().get(index);
    }
    return path(current, parts, position + 1);
  }

  private static JsonObject object(Path path) throws Exception {
    return Json.parseObject(Files.readString(path));
  }

  /**
   * Reads a required string member. A renamed or dropped field fails with the field name instead of
   * a bare NullPointerException.
   */
  private static String string(JsonObject object, String name) {
    JsonElement value = object.get(name);
    assertNotNull(value, () -> "expected field '" + name + "' in " + object);
    return value.getAsString();
  }

  /**
   * Asserts a provenance value has the shape its field promises, naming the field when it fails.
   */
  private static void assertMatches(Pattern pattern, String value, String field) {
    assertNotNull(value, () -> "missing " + field);
    assertTrue(
        pattern.matcher(value).matches(),
        () -> field + " is not of the form " + pattern.pattern() + ": " + value);
  }

  /**
   * Reads a value out of the two-column provenance table in SPEC.md.
   *
   * @param specMarkdown The full text of vendor/SPEC.md.
   * @param field The row label, without the surrounding backticks.
   * @return The row's value cell stripped of backticks and whitespace, or null when there is no
   *     such row.
   */
  private static String provenanceRow(String specMarkdown, String field) {
    Matcher row =
        Pattern.compile(
                "^\\|\\s*`" + Pattern.quote(field) + "`\\s*\\|([^|]*)\\|", Pattern.MULTILINE)
            .matcher(specMarkdown);
    return row.find() ? row.group(1).replace("`", "").trim() : null;
  }

  private static String sha256(Path path) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }

  private static Map<String, Object> map(JsonObject value) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) Json.toMap(value);
    return map;
  }

  private static void assertContains(JsonElement expected, JsonElement actual) {
    if (expected == null || expected.isJsonNull()) return;
    assertNotNull(actual, "DTO round trip dropped a required recorded field");
    if (expected.isJsonObject()) {
      assertTrue(actual.isJsonObject(), "DTO changed an object field into a scalar");
      for (Map.Entry<String, JsonElement> entry : expected.getAsJsonObject().entrySet()) {
        assertContains(entry.getValue(), actual.getAsJsonObject().get(entry.getKey()));
      }
    } else if (expected.isJsonArray()) {
      assertTrue(actual.isJsonArray(), "DTO changed an array field into a scalar");
      assertEquals(expected.getAsJsonArray().size(), actual.getAsJsonArray().size());
      for (int i = 0; i < expected.getAsJsonArray().size(); i++)
        assertContains(expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i));
    } else {
      assertEquals(expected, actual);
    }
  }

  private static final class ReplayServer implements AutoCloseable {
    private final HttpServer server;

    ReplayServer(Deque<JsonObject> recordings, List<String> captured) throws Exception {
      this(recordings, captured, false);
    }

    private ReplayServer(Deque<JsonObject> recordings, List<String> captured, boolean errorResponse)
        throws Exception {
      server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            int status = 200;
            if (errorResponse && path.endsWith("/document/audit")) {
              response =
                  "{\"error\":\"stale recording\",\"code\":\"STALE_REFERENCE\",\"detail\":\"finding\"}";
              status = 409;
            } else if ("POST".equals(exchange.getRequestMethod())) {
              captured.add(
                  path.substring(path.indexOf("/v1/ctx/") + "/v1/ctx/".length())
                      + "\n"
                      + new String(
                          exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              JsonObject recording = recordings.removeFirst();
              JsonObject output = recording.getAsJsonObject("output");
              if (output.has("error")) {
                JsonObject semanticError = output.getAsJsonObject("error");
                JsonObject wireError = new JsonObject();
                wireError.addProperty("error", string(semanticError, "message"));
                wireError.addProperty("code", string(semanticError, "code"));
                wireError.addProperty("detail", string(semanticError, "detail"));
                response = wireError.toString();
                status = 422;
              } else response = output.toString();
            } else response = resolutionFor(path);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange
                .getResponseHeaders()
                .add("X-EzClassify-Resolved-Version", contextSegments(path)[1]);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
              output.write(bytes);
            }
          });
      server.start();
    }

    /** Answers a Context read with a description of the Context the request path names. */
    private static String resolutionFor(String path) {
      String[] segments = contextSegments(path);
      return ClientStubServer.description("/v1/ctx/" + segments[0] + "/" + segments[1]);
    }

    /** The Context id and version in a {@code /v1/ctx/{id}/{version}/...} request path. */
    private static String[] contextSegments(String path) {
      int start = path.indexOf("/v1/ctx/");
      String[] segments =
          start < 0 ? new String[0] : path.substring(start + "/v1/ctx/".length()).split("/");
      return new String[] {
        segments.length > 0 ? segments[0] : "", segments.length > 1 ? segments[1] : "1.0.0"
      };
    }

    static ReplayServer error() throws Exception {
      return new ReplayServer(new ArrayDeque<>(), new ArrayList<>(), true);
    }

    String url() {
      return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
