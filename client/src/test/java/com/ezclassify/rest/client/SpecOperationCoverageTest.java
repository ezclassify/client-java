package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The vendored OpenAPI is the authority for what this client owes. Every operation it declares has
 * to reach the wire through public API: at least one recorded wire-corpus case whose driver calls a
 * public method sends it. An operation no public method sends is listed in {@link #NOT_SENT} with
 * the reason it is deliberately absent. A re-vendored contract that adds a route fails here until
 * the client grows the method or the absence is argued for.
 */
class SpecOperationCoverageTest {
  /** operationId to why this client deliberately has no public method that sends it. */
  private static final Map<String, String> NOT_SENT =
      Map.of("getReady", WireCaseDrivers.NO_SDK_OPERATION);

  private static final OpenApiSchemas SPEC = OpenApiSchemas.load();

  /** operationId to the corpus cases whose public driver sends that operation. */
  private static Map<String, Set<String>> publicCasesByOperation() throws Exception {
    Map<String, String> byRoute = new HashMap<>();
    SPEC.operations()
        .forEach((id, op) -> byRoute.put(op.method().toUpperCase() + " " + op.path(), id));
    JsonObject corpus =
        JsonParser.parseString(Files.readString(Path.of("../vendor/spec/wire/v1/corpus.json")))
            .getAsJsonObject();
    Map<String, Set<String>> covered = new TreeMap<>();
    for (JsonElement item : corpus.getAsJsonArray("cases")) {
      JsonObject entry = item.getAsJsonObject();
      String id = entry.get("id").getAsString();
      JsonObject request = entry.getAsJsonObject("request");
      if (WireCaseDrivers.DRIVERS.get(id) != WireCaseDrivers.Boundary.PUBLIC) continue;
      if (!request.has("pathTemplate")) continue;
      String route =
          request.get("method").getAsString() + " " + request.get("pathTemplate").getAsString();
      String operation = byRoute.get(route);
      if (operation != null) covered.computeIfAbsent(operation, k -> new TreeSet<>()).add(id);
    }
    return covered;
  }

  @Test
  void everyContractOperationIsSentThroughPublicApiOrExplained() throws Exception {
    Map<String, Set<String>> covered = publicCasesByOperation();
    Set<String> unaccounted = new TreeSet<>(SPEC.operations().keySet());
    unaccounted.removeAll(covered.keySet());
    unaccounted.removeAll(NOT_SENT.keySet());
    assertEquals(
        Set.of(),
        unaccounted,
        "the vendored contract declares operations no public driver sends; add the method and a"
            + " public wire driver, or list the operation in NOT_SENT with its reason");
  }

  @Test
  void anOperationListedAsNotSentReallyHasNoPublicDriver() throws Exception {
    Map<String, Set<String>> covered = publicCasesByOperation();
    for (String operation : NOT_SENT.keySet()) {
      assertTrue(SPEC.operations().containsKey(operation), operation + " is not in the contract");
      assertFalse(covered.containsKey(operation), operation + " is sent by " + covered);
    }
  }

  @Test
  void aPrivatelyDrivenCaseIsARefusalUnlessItsReasonSaysOtherwise() throws Exception {
    JsonObject corpus =
        JsonParser.parseString(Files.readString(Path.of("../vendor/spec/wire/v1/corpus.json")))
            .getAsJsonObject();
    Set<String> errorReasons =
        Set.of(
            WireCaseDrivers.CLOSED_REQUEST,
            WireCaseDrivers.UNDECLARED_QUERY,
            WireCaseDrivers.UNDECLARED_ROUTE);
    for (JsonElement item : corpus.getAsJsonArray("cases")) {
      JsonObject entry = item.getAsJsonObject();
      String reason = WireCaseDrivers.PRIVATE_REASONS.get(entry.get("id").getAsString());
      if (reason == null || !errorReasons.contains(reason)) continue;
      int status = entry.getAsJsonObject("response").get("status").getAsInt();
      boolean refused =
          WireCaseDrivers.UNDECLARED_ROUTE.equals(reason)
              ? status == 404 || status == 405
              : status >= 400 && status < 500;
      assertTrue(refused, entry.get("id").getAsString() + ": " + status + " " + reason);
      // A refused route or method is one the contract does not declare.
      assertEquals(
          WireCaseDrivers.UNDECLARED_ROUTE.equals(reason),
          !entry.getAsJsonObject("request").has("pathTemplate"),
          entry.get("id").getAsString());
    }
  }
}
