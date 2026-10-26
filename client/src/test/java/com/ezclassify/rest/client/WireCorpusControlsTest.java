package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class WireCorpusControlsTest {
  @TestFactory
  Stream<DynamicTest> canonicalRawTargetVectors() {
    String[][] pairs = {
      {"", "/"},
      {"/", "/"},
      {"/x/", "/x"},
      {"/x//", "/x/"},
      {"/x///", "/x//"},
      {"/x?b=2&a=1", "/x?a=1&b=2"},
      {"/x?z=9&z=2", "/x?z=2&z=9"},
      {"/x?flag=&a&", "/x?a&flag"},
      {"/x?&&", "/x?"},
      {"/x?", "/x"},
      {"/x?value=a=b", "/x?value=a=b"},
      {"/x?a=%2f&a=%2F", "/x?a=%2F&a=%2f"},
      {"/a%2Fb/?z=2&a=1", "/a%2Fb?a=1&z=2"},
      {"/a/b/?z=2&a=1", "/a/b?a=1&z=2"}
    };
    return Stream.of(pairs)
        .map(
            pair ->
                DynamicTest.dynamicTest(
                    "raw target: " + pair[0],
                    () -> assertEquals(pair[1], WireCorpus.target(pair[0]))));
  }

  @Test
  void objectsIgnoreKeyOrderArraysPreserveOrderAndAbsenceIsNotNull() {
    JsonElement a = json("{\"__proto__\":{\"id\":1},\"values\":[1,2]}");
    JsonElement b = json("{\"values\":[1,2],\"__proto__\":{\"id\":1}}");
    assertEquals(
        WireCorpus.key("post", "/x/?b=2&a=1", a, true),
        WireCorpus.key("POST", "/x?a=1&b=2", b, true));
    assertNotEquals(
        WireCorpus.key("POST", "/x", a, true),
        WireCorpus.key("POST", "/x", json("{\"values\":[2,1],\"__proto__\":{\"id\":1}}"), true));
    assertNotEquals(
        WireCorpus.key("POST", "/x", a, true),
        WireCorpus.key("POST", "/x", json("{\"values\":[1,2]}"), true));
    assertNotEquals(
        WireCorpus.key("GET", "/x", null, false), WireCorpus.key("GET", "/x", json("null"), true));
  }

  @Test
  void exactReplayRefusesChangedMethodTargetFieldsHeadersAndCredential() {
    var c = synthetic("context-token");
    var headers = headers();
    headers.put("x-ezclassify-context-token", List.of("fixture-context-carrier"));
    String body = c.body().toString();
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("GET", c.target(), bytes(body), headers));
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target() + "//", bytes(body), headers));
    assertThrows(
        AssertionError.class,
        () ->
            new WireCorpus.Replay(c)
                .answer("POST", c.target().replace("%2F", "/"), bytes(body), headers));
    assertThrows(
        AssertionError.class,
        () ->
            new WireCorpus.Replay(c)
                .answer(
                    "POST", c.target(), bytes("{\"values\":[2,1],\"source\":\"parse\"}"), headers));
    assertThrows(
        AssertionError.class,
        () ->
            new WireCorpus.Replay(c)
                .answer(
                    "POST", c.target(), bytes("{\"values\":[1,2],\"source\":\"user\"}"), headers));
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target(), bytes("{}"), headers));
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target(), new byte[0], headers));
    var changed = new java.util.HashMap<>(headers);
    changed.put("accept", List.of("text/plain"));
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target(), bytes(body), changed));
    var missing = new java.util.HashMap<>(headers);
    missing.remove("x-ezclassify-context-token");
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target(), bytes(body), missing));
    var leaked = new java.util.HashMap<>(headers);
    leaked.put("authorization", List.of("Bearer unrecorded"));
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target(), bytes(body), leaked));
    var repeatedCarrier = new java.util.HashMap<>(headers);
    repeatedCarrier.put(
        "x-ezclassify-context-token",
        List.of("fixture-context-carrier", "fixture-context-carrier"));
    assertThrows(
        AssertionError.class,
        () -> new WireCorpus.Replay(c).answer("POST", c.target(), bytes(body), repeatedCarrier));
    var replay = new WireCorpus.Replay(c);
    assertEquals(c, replay.answer("POST", c.target(), bytes(body), headers));
    assertEquals(1, replay.hits);
    assertThrows(
        AssertionError.class, () -> replay.answer("POST", c.target(), bytes(body), headers));
  }

  @Test
  void staleOrMissingOwnerInputsAreNotSuccessfulCoverage() throws Exception {
    var old = new WireCorpus(Map.of("old-case", synthetic("none")));
    assertThrows(AssertionError.class, () -> old.requireIds(WireCaseDrivers.DRIVERS.keySet()));
    assertThrows(
        AssertionError.class, () -> old.requireIds(java.util.Set.of("old-case", "unknown-id")));
    old.requireIds(java.util.Set.of("old-case"));
    assertThrows(
        java.nio.file.NoSuchFileException.class,
        () -> WireCorpus.load(Path.of("does-not-exist"), Path.of("does-not-exist")));
  }

  @Test
  void publicDocumentAuditStillOwnsItsOrdinaryStandardDefault() throws Exception {
    // Build a recording from the existing endpoint's exact default request, never alter corpus
    // cases.
    var body = json("{\"text\":\"\",\"responseProfile\":\"standard\"}");
    var error = json("{\"error\":\"empty document control\",\"code\":\"EMPTY_CONTROL\"}");
    var c =
        new WireCorpus.Case(
            "public-audit-default-control",
            "POST",
            "/v1/ctx/c/v/document/audit",
            body,
            true,
            json("{\"accept\":\"application/json\",\"content-type\":\"application/json\"}")
                .getAsJsonObject(),
            "none",
            400,
            error,
            json("{\"content-type\":\"application/json\"}").getAsJsonObject());
    // This id has no literal driver: invoke the public operation explicitly through the real
    // socket.
    WireCorpusTest.auditDefaultControl(c);
  }

  private static WireCorpus.Case synthetic(String credential) {
    return new WireCorpus.Case(
        "synthetic",
        "POST",
        "/v1/ctx/a%2Fb/v/op?z=2&a=1",
        json("{\"values\":[1,2],\"source\":\"parse\"}"),
        true,
        json("{\"accept\":\"application/json\",\"content-type\":\"application/json\"}")
            .getAsJsonObject(),
        credential,
        200,
        json("{\"result\":true}"),
        new JsonObject());
  }

  private static java.util.HashMap<String, List<String>> headers() {
    var result = new java.util.HashMap<String, List<String>>();
    result.put("accept", List.of("application/json"));
    result.put("content-type", List.of("application/json"));
    return result;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static JsonElement json(String value) {
    return JsonParser.parseString(value);
  }
}
