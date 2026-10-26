package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.lang.reflect.RecordComponent;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the cross-SDK cache conformance plans in {@code vendor/spec/cache/v1/plans.json} against
 * this client. Each plan builds one cache-enabled Context handle, drives it through the public API
 * over a counting replay transport, and asserts the number of requests that reached the wire.
 *
 * <p>Three capabilities are declared not applicable, each for the reason the plan schema fixes:
 * Java's public operations take no cancellation, its request types cannot carry an explicit null,
 * and it exposes one method per wire operation, so no two operations share a route and body.
 */
class CachePlanRunnerTest {
  private static final Path CACHE_SPEC = Path.of("../vendor/spec/cache/v1");
  private static final long STEP_SECONDS = 15;
  private static final HttpClient HTTP = EzClassifyContext.newHttpClient();

  /** Capabilities the plan schema lets a port declare absent. */
  private static final Set<String> NOT_APPLICABLE =
      Set.of("cancellation", "explicitNullMember", "sharedRouteOperations");

  private static final JsonObject PLANS = load("plans.json");

  private static JsonObject load(String name) {
    try {
      return JsonParser.parseString(Files.readString(CACHE_SPEC.resolve(name))).getAsJsonObject();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static List<JsonObject> plans() {
    List<JsonObject> plans = new ArrayList<>();
    for (JsonElement plan : PLANS.getAsJsonArray("plans")) plans.add(plan.getAsJsonObject());
    return plans;
  }

  private static Set<String> strings(JsonArray array) {
    Set<String> values = new TreeSet<>();
    for (JsonElement value : array) values.add(value.getAsString());
    return values;
  }

  private static boolean runs(JsonObject plan) {
    Set<String> needs = strings(plan.getAsJsonArray("capabilities"));
    return Collections.disjoint(needs, NOT_APPLICABLE);
  }

  @Test
  void onlyCapabilitiesThePlanSchemaAllowsAreDeclaredNotApplicable() {
    JsonObject capabilities = PLANS.getAsJsonObject("capabilities");
    for (String name : NOT_APPLICABLE)
      assertTrue(capabilities.getAsJsonObject(name).has("notApplicable"), name);
    // Every other capability is required of every port, so each is implemented below.
    Set<String> implemented =
        Set.of("responseGate", "clock", "scriptedResponses", "quiesce", "schemeResourceOperations");
    Set<String> required = new TreeSet<>(capabilities.keySet());
    required.removeAll(NOT_APPLICABLE);
    assertEquals(new TreeSet<>(implemented), required);
  }

  @Test
  void everyPlanIsRunOrDeclaredNotApplicable() {
    long run = plans().stream().filter(CachePlanRunnerTest::runs).count();
    long skipped = plans().size() - run;
    assertEquals(60, plans().size());
    assertEquals(5, skipped, "cancellation x3, explicitNullMember x1, sharedRouteOperations x1");
    assertEquals(
        Set.of(
            "cancel-one-caller",
            "cancel-before-and-after",
            "cancel-last-caller",
            "key-explicit-null",
            "shared-route-separate-entries"),
        Set.copyOf(
            plans().stream()
                .filter(plan -> !runs(plan))
                .map(plan -> plan.get("id").getAsString())
                .toList()));
  }

  @TestFactory
  Stream<DynamicTest> everyApplicablePlan() throws Exception {
    WireCorpus corpus = WireCorpus.vendored();
    return plans().stream()
        .filter(CachePlanRunnerTest::runs)
        .map(
            plan ->
                DynamicTest.dynamicTest(
                    plan.get("id").getAsString(), () -> new PlanRun(corpus, plan).execute()));
  }

  /** One caller's settled call: the result it was handed, or the failure it saw. */
  private record Outcome(
      Object result, Throwable error, WireCorpus.Case recorded, String variant, String member) {}

  /** The clock the response cache reads, moved by plans instead of by waiting. */
  private static final class ShiftedClock implements LongSupplier {
    private final AtomicLong shiftNanos = new AtomicLong();

    @Override
    public long getAsLong() {
      return System.nanoTime() + shiftNanos.get();
    }

    void advance(long millis) {
      shiftNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
  }

  private static final class PlanRun {
    private final WireCorpus corpus;
    private final JsonObject plan;
    private final String id;
    private final ShiftedClock clock = new ShiftedClock();
    private final ExecutorService callers =
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "cache-plan-caller");
              thread.setDaemon(true);
              return thread;
            });
    private final Map<String, CompletableFuture<Outcome>> named = new LinkedHashMap<>();
    private final List<CompletableFuture<Outcome>> inFlight = new ArrayList<>();
    private final String denialCase;
    private CacheReplayTransport transport;
    private CacheReplayTransport reference;
    private ResponseCache cache;
    private EzClassifyContext context;

    PlanRun(WireCorpus corpus, JsonObject plan) {
      this.corpus = corpus;
      this.plan = plan;
      this.id = plan.get("id").getAsString();
      this.denialCase = PLANS.get("denialCase").getAsString();
    }

    void execute() throws Exception {
      JsonObject client = plan.getAsJsonObject("client");
      String binding = client.get("binding").getAsString();
      // A pinned binding names its version. An unpinned one asks for latest by name. A
      // token-defaulted binding names none: the Context token supplies it and the paths carry no
      // version segment.
      String version =
          switch (binding) {
            case "pinned" -> "1.0.0";
            case "unpinned" -> "latest";
            case "token-defaulted" -> null;
            default -> throw new AssertionError("unknown binding " + binding);
          };
      boolean decisions =
          client.has("cacheAccessDecisions") && client.get("cacheAccessDecisions").getAsBoolean();
      String contextId = client.has("context") ? client.get("context").getAsString() : "ez-full";
      transport = new CacheReplayTransport(corpus, denialCase, version);
      try {
        if (client.has("construction")) {
          assertEquals("refused", client.get("construction").getAsString(), id);
          assertThrows(IllegalArgumentException.class, () -> build(contextId, version, decisions));
          assertEquals(0, plan.getAsJsonArray("steps").size(), id);
          return;
        }
        build(contextId, version, decisions);
        context.resolve();
        transport.resetCounts();
        for (JsonElement step : plan.getAsJsonArray("steps")) run(step.getAsJsonObject());
        for (CompletableFuture<Outcome> call : inFlight) settle(call);
        assertEquals(List.of(), transport.unmatched(), id + ": requests no case recorded");
      } finally {
        transport.close();
        if (reference != null) reference.close();
        callers.shutdownNow();
      }
    }

    private void build(String contextId, String version, boolean decisions) {
      cache = new ResponseCache(clock);
      context =
          new EzClassifyContext(
              transport.baseUrl(),
              contextId,
              version,
              new RestClientCredentials(null, "conformance-context-carrier"),
              HTTP,
              new ContextCacheOptions(decisions, false),
              cache,
              EzClassifyContext.REQUEST_TIMEOUT);
    }

    private void run(JsonObject step) throws Exception {
      String kind = step.get("step").getAsString();
      switch (kind) {
        case "call" -> call(step);
        case "concurrent" -> concurrent(step);
        case "gate" -> transport.gate(step.get("case").getAsString());
        case "release" -> {
          transport.release(step.get("case").getAsString());
          for (CompletableFuture<Outcome> call : inFlight) settle(call);
        }
        case "advanceClock" -> clock.advance(step.get("ms").getAsLong());
        case "respond" -> respond(step);
        // Java starts no work after a response: a moved resolved version clears the cache and
        // drops the held Context description on the response's own thread, so there is nothing
        // to wait for.
        case "quiesce" -> {}
        case "mutateResult" -> mutate(outcome(step.get("caller").getAsString()).result());
        case "expectOk" -> {
          for (String name : names(step)) {
            Outcome outcome = outcome(name);
            if (outcome.error() != null)
              fail(id + ": " + name + " failed: " + outcome.error(), outcome.error());
          }
        }
        case "expectError" -> {
          for (String name : names(step))
            assertFailure(step.getAsJsonObject("error"), outcome(name).error(), name);
        }
        case "expectWireCount" -> {
          assertEquals(
              false, step.has("variant"), id + ": explicit-null counts need the capability");
          assertEquals(
              step.get("count").getAsInt(),
              transport.count(step.get("case").getAsString()),
              id + ": wire requests for " + step.get("case").getAsString());
        }
        case "expectIndependentResults" -> {
          for (String name : names(step)) assertIndependent(name, outcome(name));
        }
        // cancel, expectWireAborted and preCanceled belong to plans that need the cancellation
        // capability, which this client declares not applicable, so none of them is ever run.
        default -> throw new AssertionError(id + ": step this runner cannot run: " + kind);
      }
    }

    private void call(JsonObject step) {
      assertEquals(false, step.has("preCanceled"), id);
      int times = step.has("times") ? step.get("times").getAsInt() : 1;
      for (int i = 0; i < times; i++) {
        Outcome outcome = invoke(step);
        if (step.has("expect"))
          assertFailure(
              step.getAsJsonObject("expect").getAsJsonObject("error"), outcome.error(), "call");
        else if (outcome.error() != null)
          fail(id + ": call failed: " + outcome.error(), outcome.error());
        if (step.has("caller"))
          named.put(step.get("caller").getAsString(), CompletableFuture.completedFuture(outcome));
      }
    }

    private void concurrent(JsonObject step) throws Exception {
      String caseId = step.get("case").getAsString();
      Set<String> names = strings(step.getAsJsonArray("callers"));
      long before = cache.arrivals();
      List<CompletableFuture<Outcome>> started = new ArrayList<>();
      for (String name : names) {
        CompletableFuture<Outcome> call =
            CompletableFuture.supplyAsync(() -> invoke(step), callers);
        named.put(name, call);
        started.add(call);
        inFlight.add(call);
      }
      if (!transportGated(caseId)) {
        for (CompletableFuture<Outcome> call : started) settle(call);
        return;
      }
      // Observed, not assumed: the held request has reached the transport and the cache has seen
      // every caller, so each one has joined the in-flight entry before the step ends.
      transport.awaitHeld(caseId);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STEP_SECONDS);
      while (cache.arrivals() - before < names.size()) {
        if (System.nanoTime() > deadline)
          fail(id + ": only " + (cache.arrivals() - before) + " of " + names.size() + " joined");
        Thread.sleep(2);
      }
    }

    private boolean transportGated(String caseId) {
      for (JsonElement step : plan.getAsJsonArray("steps")) {
        JsonObject candidate = step.getAsJsonObject();
        if ("gate".equals(candidate.get("step").getAsString())
            && caseId.equals(candidate.get("case").getAsString())) return true;
      }
      return false;
    }

    private Outcome invoke(JsonObject step) {
      WireCorpus.Case recorded = corpus.cases.get(step.get("case").getAsString());
      String variant = step.has("variant") ? step.get("variant").getAsString() : null;
      String member = step.has("member") ? step.get("member").getAsString() : null;
      try {
        return new Outcome(
            WireCaseDrivers.invoke(recorded, context, variant, member),
            null,
            recorded,
            variant,
            member);
      } catch (RuntimeException failure) {
        return new Outcome(null, failure, recorded, variant, member);
      }
    }

    private void respond(JsonObject step) {
      switch (step.get("synthetic").getAsString()) {
        case "malformed-success" ->
            transport.scriptMalformedSuccess(
                step.get("case").getAsString(),
                new ArrayList<>(strings(step.getAsJsonArray("removeMembers"))));
        case "denial-404" -> transport.scriptDenial(step.get("case").getAsString());
        case "resolved-version" ->
            transport.scriptResolvedVersion(step.get("version").getAsString());
        default -> throw new AssertionError(id + ": unknown scripted response");
      }
    }

    private static Set<String> names(JsonObject step) {
      return strings(step.getAsJsonArray("callers"));
    }

    private Outcome outcome(String name) throws Exception {
      CompletableFuture<Outcome> call = named.get(name);
      WireCorpus.require(call != null, id + ": no caller named " + name);
      return settle(call);
    }

    private Outcome settle(CompletableFuture<Outcome> call) throws Exception {
      return call.get(STEP_SECONDS, TimeUnit.SECONDS);
    }

    private void assertFailure(JsonObject expected, Throwable error, String who) {
      assertInstanceOf(EzClassifyClientException.class, error, id + ": " + who + " should fail");
      EzClassifyClientException failure = (EzClassifyClientException) error;
      switch (expected.get("kind").getAsString()) {
        case "http" ->
            assertEquals(expected.get("status").getAsInt(), failure.statusCode(), id + ": " + who);
        // The wire answered 200, so a failure with no HTTP status and no transport cause is the
        // client refusing the body it was handed.
        case "malformed-response" -> {
          assertEquals(0, failure.statusCode(), id + ": " + who);
          assertTrue(
              failure.getCause() == null || failure.getCause() instanceof JsonParseException,
              id + ": " + who + " failed in transport: " + failure.getCause());
        }
        default -> throw new AssertionError(id + ": failure this runner cannot expect");
      }
    }

    /** The caller's result still equals what an uncached call decodes from the same response. */
    private void assertIndependent(String name, Outcome outcome) throws Exception {
      if (reference == null) {
        reference = new CacheReplayTransport(corpus, denialCase, "1.0.0");
      }
      EzClassifyContext fresh =
          new EzClassifyContext(
              reference.baseUrl(),
              context.contextId(),
              "1.0.0",
              new RestClientCredentials(null, "conformance-context-carrier"),
              HTTP,
              new ContextCacheOptions(false, true));
      Object decoded =
          WireCaseDrivers.invoke(outcome.recorded(), fresh, outcome.variant(), outcome.member());
      assertEquals(WireJson.toTree(decoded), WireJson.toTree(outcome.result()), id + ": " + name);
    }

    /** Change a result in place as far as its types allow; an immutable one satisfies the step. */
    private static void mutate(Object value) throws ReflectiveOperationException {
      mutate(value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void mutate(Object value, Set<Object> seen) throws ReflectiveOperationException {
      if (value == null || !seen.add(value)) return;
      if (value instanceof JsonObject object) {
        for (String key : List.copyOf(object.keySet())) object.remove(key);
      } else if (value instanceof JsonArray array) {
        while (array.size() > 0) array.remove(0);
      } else if (value instanceof List<?> list) {
        for (Object element : list) mutate(element, seen);
        try {
          list.clear();
        } catch (UnsupportedOperationException immutable) {
          // An immutable list refuses the change, which is what the step asks of a result.
        }
      } else if (value instanceof Map<?, ?> map) {
        for (Object element : map.values()) mutate(element, seen);
        try {
          map.clear();
        } catch (UnsupportedOperationException immutable) {
          // Refused, as above.
        }
      } else if (value.getClass().isRecord()) {
        for (RecordComponent component : value.getClass().getRecordComponents())
          mutate(component.getAccessor().invoke(value), seen);
      }
    }
  }
}
