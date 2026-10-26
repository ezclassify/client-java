package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Engine-slice conformance tests for the EzClassify Java client.
 *
 * <p>Reads every engine-slice plan from {@code vendor/spec/plans/v1/}, pre-populates a local HTTP
 * {@link Replayer} with the matching recorded responses from {@code vendor/spec/recordings/v1/},
 * and runs each plan case against the replayer using the same canonical API path and request body
 * that the real REST API serves.
 *
 * <p>Server-slice plans ({@code "slice":"server"}) and unsupported methods are skipped
 * automatically. The suite reads <em>only</em> from {@code vendor/spec/}; no network access or
 * running server is required.
 *
 * <p>The lookup algorithm bridging recording keys to replayer keys mirrors the Go conformance
 * runner:
 *
 * <ul>
 *   <li>Recording key: {@code
 *       sha256(method+"|"+stableJson(args)+"|"+stableJson(fixture)+"|"+mode+"|"+ctxId+"|"+ctxVersion)}
 *   <li>Replayer key: {@code sha256(normalizedPath+"|"+stableJson(body))}
 * </ul>
 */
@SuppressWarnings({"restriction", "unchecked"})
class ConformanceTest {

  private static final Path VENDOR_BASE =
      Path.of(System.getProperty("user.dir")).resolve("..").resolve("vendor/spec").normalize();

  /** One pre-built test case ready to run against the replayer. */
  private record TestCase(String name, String path, String bodyJson, Object expect) {}

  /**
   * Number of engine-slice plan cases the runner must execute against the vendored spec bundle.
   * Plan-declared skips (server slice, fromLayers mode, multi-fixture cases, methods this runner
   * does not route) are excluded by construction; everything else must run. The count is a pinned
   * golden: when the vendored bundle is regenerated, update it deliberately in the same commit.
   * Without it, a missing recording or unreadable fixture silently shrinks the suite.
   */
  private static final int EXPECTED_CASE_COUNT = 123;

  private static Replayer replayer;
  private static List<TestCase> testCases;
  private static List<String> droppedCases;

  @BeforeAll
  static void setUp() throws Exception {
    Path recordingsDir = VENDOR_BASE.resolve("recordings").resolve("v1");
    Path plansDir = VENDOR_BASE.resolve("plans").resolve("v1");
    Path fixtureDir = VENDOR_BASE.resolve("fixtures").resolve("v1");

    // Load all recordings: recordingHash → raw response JSON bytes.
    Map<String, byte[]> allRecordings = loadAllRecordings(recordingsDir);

    // Build the replayer recording map and the ordered list of test cases simultaneously.
    Map<String, byte[]> replayerMap = new HashMap<>();
    testCases = new ArrayList<>();
    droppedCases = new ArrayList<>();

    for (Path planFile : listJsonFiles(plansDir)) {
      Map<String, Object> plan =
          (Map<String, Object>) Replayer.parseJson(Files.readString(planFile));
      String slice = (String) plan.get("slice");
      if (!"engine".equals(slice)) continue;

      List<Object> cases = (List<Object>) plan.get("cases");
      if (cases == null) continue;
      String planName = planFile.getFileName().toString().replace(".json", "");

      for (Object caseObj : cases) {
        Map<String, Object> tc = (Map<String, Object>) caseObj;
        String name = planName + "/" + tc.get("name");

        String method = (String) tc.get("method");
        List<Object> args =
            tc.get("args") instanceof List
                ? (List<Object>) tc.get("args")
                : Collections.emptyList();
        Object fixtureRaw = tc.get("fixture");
        String mode = (String) tc.getOrDefault("mode", "");
        if (mode == null || mode.isEmpty()) mode = "fromJSON";
        String ctxId = (String) tc.getOrDefault("ctxId", "");
        if (ctxId == null) ctxId = "";
        String ctxVersion = (String) tc.getOrDefault("ctxVersion", "");
        if (ctxVersion == null) ctxVersion = "";
        String guideFixtureRaw = (String) tc.get("guideFixture");
        Object expect = tc.get("expect");

        // Plan-declared skips: from-layers mode and multi-fixture cases are out of this
        // runner's scope by construction, so they are the only silent continues allowed here.
        if ("fromLayers".equals(mode)) continue;
        if (!(fixtureRaw instanceof String)) continue;
        String fixtureName = (String) fixtureRaw;

        // Load scheme ID from fixture. A missing or unreadable fixture is a corpus defect,
        // not a skip: record it so the count test fails with the case name.
        String schemeId = loadSchemeId(fixtureDir, fixtureName);
        if (schemeId == null) {
          droppedCases.add(name + " (unreadable scheme fixture: " + fixtureName + ")");
          continue;
        }

        // Load guide fixture if needed; same rule, a guide case without its guide is a defect.
        String guideFixtureName = guideFixtureRaw != null ? guideFixtureRaw : fixtureName;
        Map<String, Object> guideData = null;
        String guideId = null;
        if (needsGuide(method)) {
          guideData = loadGuideFixture(fixtureDir, guideFixtureName);
          if (guideData == null) {
            droppedCases.add(name + " (unreadable guide fixture: " + guideFixtureName + ")");
            continue;
          }
          guideId = guideIdFrom(guideData, guideFixtureName);
        }

        // Compute recording key:
        // sha256(method|stableJson(args)|stableJson(fixture)|mode|ctxId|ctxVersion)
        String recKey = computeRecordingKey(method, args, fixtureRaw, mode, ctxId, ctxVersion);
        byte[] recResponse = allRecordings.get(recKey);
        if (recResponse == null) {
          droppedCases.add(name + " (no recording for key " + recKey + ")");
          continue;
        }

        // Build canonical HTTP path and body for this plan case.
        String[] pathAndBody = buildPlanBody(method, args, schemeId, guideId, guideData);
        if (pathAndBody == null) continue; // method not routed by this runner (plan-declared)

        String apiPath = pathAndBody[0];
        String bodyJson = pathAndBody[1];

        // Compute replayer key: sha256(normalizedPath|stableJson(body)).
        Object parsedBody = Replayer.parseJson(bodyJson);
        String rKey = Replayer.recordingKey(apiPath, parsedBody);
        putRecording(replayerMap, rKey, recResponse, name);

        testCases.add(new TestCase(name, apiPath, bodyJson, expect));
      }
    }

    replayer = new Replayer(replayerMap);
  }

  @AfterAll
  static void tearDown() {
    if (replayer != null) replayer.close();
  }

  @TestFactory
  Stream<DynamicTest> conformanceCases() {
    if (testCases == null || testCases.isEmpty()) {
      return Stream.empty();
    }
    return testCases.stream().map(tc -> DynamicTest.dynamicTest(tc.name(), () -> runCase(tc)));
  }

  /**
   * Guards the suite against silent shrinkage: a case that loses its recording or fixture must fail
   * here by name instead of quietly not running, and the executed-case total is pinned so a new
   * drop channel cannot slip in unnoticed.
   */
  @Test
  void everyEligibleCaseIsExecuted() {
    if (!droppedCases.isEmpty()) {
      fail("Plan cases dropped instead of executed:\n  " + String.join("\n  ", droppedCases));
    }
    assertEquals(
        EXPECTED_CASE_COUNT,
        testCases.size(),
        "Executed conformance case count drifted from the pinned corpus count; if the vendored"
            + " spec bundle changed deliberately, update EXPECTED_CASE_COUNT in the same commit");
  }

  private static void runCase(TestCase tc) throws Exception {
    byte[] bodyBytes = tc.bodyJson().getBytes(StandardCharsets.UTF_8);
    URL url = new URL(replayer.url() + tc.path());
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
    conn.getOutputStream().write(bodyBytes);

    int status = conn.getResponseCode();
    InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    byte[] respBytes = stream != null ? stream.readAllBytes() : new byte[0];
    String respStr = new String(respBytes, StandardCharsets.UTF_8);

    if (status == 404) {
      Object missEnv = Replayer.parseJson(respStr);
      if (missEnv instanceof Map && "no_recording".equals(((Map<?, ?>) missEnv).get("error"))) {
        fail("no recording for " + tc.name() + " path=" + tc.path() + " body=" + tc.bodyJson());
      }
      fail("replayer 404 for " + tc.name() + ": " + respStr);
    }
    if (status != 200) {
      fail("replayer returned " + status + " for " + tc.name() + ": " + respStr);
    }

    Object actual = Replayer.parseJson(respStr);
    if (!ReplayerContractTest.subsetMatch(actual, tc.expect())) {
      fail(
          "subset mismatch for "
              + tc.name()
              + "\n  want="
              + Replayer.stableJson(tc.expect())
              + "\n  got="
              + respStr);
    }
  }

  // ── Recording / fixture helpers ──────────────────────────────────────────────────────────

  /** Load every *.recordings.json in {@code dir}, return flat map recordingHash → responseBytes. */
  private static Map<String, byte[]> loadAllRecordings(Path dir) throws Exception {
    Map<String, byte[]> result = new HashMap<>();
    for (Path file : listJsonFiles(dir)) {
      if (!file.getFileName().toString().endsWith(".recordings.json")) continue;
      Map<String, Object> rec = (Map<String, Object>) Replayer.parseJson(Files.readString(file));
      Map<String, Object> responses = (Map<String, Object>) rec.get("responses");
      if (responses == null) continue;
      for (Map.Entry<String, Object> entry : responses.entrySet()) {
        Map<String, Object> resp = (Map<String, Object>) entry.getValue();
        Object responseObj = resp.get("response");
        byte[] bytes =
            ReplayerContractTest.serializeToJson(responseObj).getBytes(StandardCharsets.UTF_8);
        putRecording(result, entry.getKey(), bytes, file.getFileName().toString());
      }
    }
    return result;
  }

  /** Read the scheme ID from a fixture file (under the {@code "scheme"} key). */
  private static String loadSchemeId(Path fixtureDir, String fixtureName) {
    try {
      Map<String, Object> fixture =
          (Map<String, Object>)
              Replayer.parseJson(Files.readString(fixtureDir.resolve(fixtureName + ".json")));
      Map<String, Object> scheme = (Map<String, Object>) fixture.get("scheme");
      return scheme != null ? (String) scheme.get("id") : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Load a guide fixture file and return it as a parsed Map (the file is the guide itself). */
  private static Map<String, Object> loadGuideFixture(Path fixtureDir, String guideName) {
    try {
      return (Map<String, Object>)
          Replayer.parseJson(Files.readString(fixtureDir.resolve(guideName + ".json")));
    } catch (Exception e) {
      return null;
    }
  }

  /** Extract the guide ID from the loaded guide data, falling back to the fixture name. */
  private static String guideIdFrom(Map<String, Object> guideData, String fallback) {
    // Flat guide fixture: {"id": "...", "rules": [...]}
    if (guideData.containsKey("id")) return (String) guideData.get("id");
    // Nested guide fixture: {"guide": {"id": "..."}}
    Object nested = guideData.get("guide");
    if (nested instanceof Map) {
      Object id = ((Map<?, ?>) nested).get("id");
      if (id instanceof String) return (String) id;
    }
    return fallback;
  }

  /** List all *.json files in {@code dir}, sorted by name. */
  private static List<Path> listJsonFiles(Path dir) throws Exception {
    List<Path> files = new ArrayList<>();
    try (var stream = Files.list(dir)) {
      stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
    }
    return files;
  }

  // ── Hash computation ─────────────────────────────────────────────────────────────────────

  /**
   * Compute the recording key using the canonical hash algorithm: {@code
   * sha256(method+"|"+stableJson(args)+"|"+stableJson(fixture)+"|"+mode+"|"+ctxId+"|"+ctxVersion)}.
   */
  private static String computeRecordingKey(
      String method, Object args, Object fixture, String mode, String ctxId, String ctxVersion) {
    String raw =
        method
            + "|"
            + Replayer.stableJson(args)
            + "|"
            + Replayer.stableJson(fixture)
            + "|"
            + mode
            + "|"
            + ctxId
            + "|"
            + ctxVersion;
    return Replayer.sha256(raw);
  }

  // ── Request body construction ────────────────────────────────────────────────────────────

  /**
   * Map a plan-case method to a canonical API path and JSON request body string. Returns {@code
   * null} for unsupported / skipped methods.
   */
  private static String[] buildPlanBody(
      String method,
      List<Object> args,
      String schemeId,
      String guideId,
      Map<String, Object> guideData) {

    String s0 = strArg(args, 0);
    List<Object> arr0 = listArg(args, 0);

    Map<String, Object> body = new LinkedHashMap<>();
    String path;
    String ctxBase = "/v1/ctx/conformance-" + segment(schemeId) + "/1.0.0";

    switch (method) {
      case "registryProcessAll":
      case "registrySuggestAcross":
        return null;
      case "process":
        path = ctxBase + "/scheme/process";
        // arg0 is marking text in most plans, but the scope-transport plan drives process from
        // facts already chosen (an assertion array), which the wire carries as `assertions` beside
        // an empty `input`. arg1 is an optional options object. Route each shape to the matching
        // request field so distinct cases produce distinct request bodies.
        if (args.size() > 0 && args.get(0) instanceof List) {
          body.put("input", "");
          body.put("assertions", args.get(0));
        } else {
          body.put("input", s0);
        }
        putOptions(body, args, 1);
        break;
      case "validate":
        path = ctxBase + "/scheme/validate";
        putExclusiveInput(body, args, s0);
        putOptions(body, args, 1);
        break;
      case "suggest":
        path = ctxBase + "/scheme/suggest";
        putExclusiveInput(body, args, s0);
        putOptions(body, args, 1);
        break;
      case "normalize":
        path = ctxBase + "/scheme/normalize";
        body.put("input", s0);
        putOptions(body, args, 1);
        break;
      case "scan":
        path = ctxBase + "/scheme/scan";
        body.put("text", s0);
        putOptions(body, args, 1);
        break;
      case "aggregate":
        path = ctxBase + "/scheme/aggregate";
        body.put("inputs", arr0);
        putOptions(body, args, 1);
        break;
      case "aggregateAssertions":
        // Lossless-transport aggregate: arg0 is an array of assertion arrays (one inner array per
        // marking), arg1 an optional canonical options object. Routes to the same
        // Context aggregate endpoint via the mutually-exclusive assertionSets body field.
        path = ctxBase + "/scheme/aggregate";
        body.put("assertionSets", arr0);
        putOptions(body, args, 1);
        break;
      case "render":
        // The second argument is the optional render format-id string.
        path = ctxBase + "/scheme/render";
        body.put("assertions", arr0);
        if (args.size() > 1 && args.get(1) instanceof String format) body.put("format", format);
        break;
      case "diff":
        path = ctxBase + "/scheme/diff";
        body.put("a", s0);
        body.put("b", strArg(args, 1));
        putOptions(body, args, 2);
        break;
      case "checkAccess":
        // Access policy bound to the context; payload-only body.
        path = ctxBase + "/access/check";
        body.put("assertions", args.size() > 0 ? args.get(0) : Collections.emptyList());
        body.put("userAttributes", args.size() > 1 ? args.get(1) : Collections.emptyMap());
        break;
      // Guide methods. evaluateMany over the plan's single-guide fixtures is
      // equivalent to a single-guide evaluate, so it folds onto the ctx
      // guide/evaluate route (the same fold the client runner uses).
      case "evaluateGuide":
      case "evaluateMany":
        if (guideId == null || guideData == null) return null;
        path = ctxBase + "/guide/evaluate";
        body.put("guideId", guideId);
        body.put("selectedRuleIds", arr0);
        break;
      case "guideScan":
        if (guideId == null || guideData == null) return null;
        path = ctxBase + "/guide/scan";
        body.put("text", s0);
        body.put("guideId", guideId);
        break;
      default:
        return null;
    }

    return new String[] {path, Replayer.stableJson(body)};
  }

  private static String segment(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean needsGuide(String method) {
    return "evaluateGuide".equals(method)
        || "guideScan".equals(method)
        || "evaluateMany".equals(method);
  }

  private static String strArg(List<Object> args, int i) {
    if (i < args.size() && args.get(i) instanceof String) return (String) args.get(i);
    return "";
  }

  private static List<Object> listArg(List<Object> args, int i) {
    if (i < args.size() && args.get(i) instanceof List) return (List<Object>) args.get(i);
    return Collections.emptyList();
  }

  private static void putExclusiveInput(Map<String, Object> body, List<Object> args, String text) {
    if (!args.isEmpty() && args.get(0) instanceof List) {
      body.put("assertions", args.get(0));
    } else {
      body.put("input", text);
    }
  }

  /** Carry declared canonical options without overwriting an operand. */
  private static void putOptions(Map<String, Object> body, List<Object> args, int index) {
    if (index >= args.size() || !(args.get(index) instanceof Map<?, ?> fields)) return;
    for (Map.Entry<?, ?> entry : fields.entrySet()) {
      String key = (String) entry.getKey();
      if (body.containsKey(key)
          && !sameJson(Replayer.stableJson(body.get(key)), Replayer.stableJson(entry.getValue()))) {
        throw new IllegalArgumentException("Conflicting plan request field: " + key);
      }
      body.put(key, entry.getValue());
    }
  }

  /** Refuse ambiguous request identities rather than replaying whichever answer was read last. */
  private static void putRecording(
      Map<String, byte[]> recordings, String key, byte[] response, String caseName) {
    byte[] previous = recordings.get(key);
    if (previous != null
        && !sameJson(
            new String(previous, StandardCharsets.UTF_8),
            new String(response, StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException(
          "Conflicting recording identity for " + caseName + ": " + key);
    }
    recordings.putIfAbsent(key, response);
  }

  private static boolean sameJson(String left, String right) {
    return JsonParser.parseString(left).equals(JsonParser.parseString(right));
  }
}
