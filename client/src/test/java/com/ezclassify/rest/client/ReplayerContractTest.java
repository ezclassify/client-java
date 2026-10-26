package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Contract test for the {@link Replayer}.
 *
 * <p>Loads every case from {@code vendor/spec/replayer-contract/v1/corpus.json} and runs each one
 * against a fresh {@link Replayer} instance. Every corpus case must pass before the replayer is
 * used for engine-slice conformance. The corpus covers path and array ordering, JSON wire-key
 * order, and the miss-envelope shape on a 404.
 */
@SuppressWarnings({"restriction", "unchecked"})
class ReplayerContractTest {

  private static final Path VENDOR_BASE =
      Path.of(System.getProperty("user.dir")).resolve("..").resolve("vendor/spec").normalize();

  @TestFactory
  Collection<DynamicTest> replayerContractCases() throws Exception {
    Path corpusPath = VENDOR_BASE.resolve("replayer-contract").resolve("v1").resolve("corpus.json");
    assertTrue(Files.exists(corpusPath), "corpus not found at " + corpusPath);

    Map<String, Object> corpus =
        (Map<String, Object>) Replayer.parseJson(Files.readString(corpusPath));
    List<Object> cases = (List<Object>) corpus.get("cases");

    List<DynamicTest> tests = new ArrayList<>();
    for (Object caseObj : cases) {
      Map<String, Object> tc = (Map<String, Object>) caseObj;
      String id = (String) tc.get("id");
      tests.add(DynamicTest.dynamicTest(id, () -> runContractCase(tc)));
    }
    return tests;
  }

  private void runContractCase(Map<String, Object> tc) throws Exception {
    String id = (String) tc.get("id");

    // Build recordings map: the corpus provides pre-computed hash keys.
    Map<String, Object> replayerRecordings = (Map<String, Object>) tc.get("replayerRecordings");
    Map<String, byte[]> recordingsMap = new HashMap<>();
    if (replayerRecordings != null) {
      for (Map.Entry<String, Object> entry : replayerRecordings.entrySet()) {
        Map<String, Object> rec = (Map<String, Object>) entry.getValue();
        Object body = rec.get("body");
        // Serialize the recording body as JSON bytes that the replayer will return.
        byte[] bodyBytes = serializeToJson(body).getBytes(StandardCharsets.UTF_8);
        recordingsMap.put(entry.getKey(), bodyBytes);
      }
    }

    try (Replayer replayer = new Replayer(recordingsMap)) {
      Map<String, Object> request = (Map<String, Object>) tc.get("request");
      String method = Objects.toString(request.get("method"), "POST");
      String requestPath = (String) request.get("path");
      Map<String, Object> headers = (Map<String, Object>) request.get("headers");
      Object requestBodyObj = request.get("body");

      String bodyJson =
          request.containsKey("rawBody")
              ? (String) request.get("rawBody")
              : serializeToJson(requestBodyObj);
      byte[] bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);

      URL url = new URL(replayer.url() + requestPath);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod(method);
      conn.setDoOutput(true);
      if (headers != null) {
        for (Map.Entry<String, Object> h : headers.entrySet()) {
          conn.setRequestProperty(h.getKey(), String.valueOf(h.getValue()));
        }
      }
      if (conn.getRequestProperty("Content-Type") == null) {
        conn.setRequestProperty("Content-Type", "application/json");
      }
      conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
      conn.getOutputStream().write(bodyBytes);

      int status = conn.getResponseCode();
      InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
      byte[] respBytes = stream != null ? stream.readAllBytes() : new byte[0];
      String respStr = new String(respBytes, StandardCharsets.UTF_8);

      if (request.containsKey("rawBody")) {
        assertEquals(
            request.get("rawBody"),
            new String(replayer.lastRequestBody(), StandardCharsets.UTF_8),
            "case=" + id + " raw request body mismatch");
      }

      int expectedStatus = ((Number) tc.get("expectedStatus")).intValue();
      assertEquals(expectedStatus, status, "case=" + id + " status mismatch; body=" + respStr);

      Object actual = Replayer.parseJson(respStr);

      if (expectedStatus == 200 && tc.containsKey("expectedBody")) {
        Object expected = tc.get("expectedBody");
        assertTrue(
            subsetMatch(actual, expected),
            "case="
                + id
                + " body mismatch\n  want="
                + Replayer.stableJson(expected)
                + "\n  got="
                + respStr);
      }

      if (expectedStatus == 404 && tc.containsKey("expectedMissEnvelope")) {
        Object expected = tc.get("expectedMissEnvelope");
        assertTrue(
            subsetMatch(actual, expected),
            "case="
                + id
                + " miss envelope mismatch\n  want="
                + Replayer.stableJson(expected)
                + "\n  got="
                + respStr);
      }
    }
  }

  /**
   * Serialize an arbitrary parsed-JSON value to a JSON string. Delegates to {@link
   * Replayer#stableJson}, whose sorted-key form is a valid serialization here: every consumer
   * either re-parses the bytes or hashes them through the key-sorting canonicalizer, so object key
   * order carries no meaning.
   */
  static String serializeToJson(Object v) {
    return Replayer.stableJson(v);
  }

  /**
   * Recursively verify that every key in {@code expected} is present and equal in {@code actual}.
   * Arrays match element-by-element up to expected's length.
   */
  static boolean subsetMatch(Object actual, Object expected) {
    if (expected == null) return actual == null;
    if (expected instanceof Boolean) return expected.equals(actual);
    if (expected instanceof Number) {
      if (!(actual instanceof Number)) return false;
      return ((Number) expected).doubleValue() == ((Number) actual).doubleValue();
    }
    if (expected instanceof String) return expected.equals(actual);
    if (expected instanceof List) {
      if (!(actual instanceof List)) return false;
      List<Object> exp = (List<Object>) expected;
      List<Object> act = (List<Object>) actual;
      if (act.size() < exp.size()) return false;
      for (int i = 0; i < exp.size(); i++) {
        if (!subsetMatch(act.get(i), exp.get(i))) return false;
      }
      return true;
    }
    if (expected instanceof Map) {
      if (!(actual instanceof Map)) return false;
      Map<String, Object> exp = (Map<String, Object>) expected;
      Map<String, Object> act = (Map<String, Object>) actual;
      for (Map.Entry<String, Object> entry : exp.entrySet()) {
        if (!subsetMatch(act.get(entry.getKey()), entry.getValue())) return false;
      }
      return true;
    }
    return Objects.equals(actual, expected);
  }
}
