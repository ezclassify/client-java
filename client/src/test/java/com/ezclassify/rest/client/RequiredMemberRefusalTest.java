package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * A success body that lacks a member the contract requires, at any depth, or carries null where the
 * contract does not allow it, is refused with {@link MalformedResponseException} naming the member.
 * Every recorded success in the wire corpus is replayed through the public call that sends it, once
 * per required member with that member removed and once with it set to null. What counts as
 * required comes from the vendored OpenAPI response schema, read independently of the generated
 * table the client walks.
 */
class RequiredMemberRefusalTest {
  private static final OpenApiSchemas SPEC = OpenApiSchemas.load();
  private static final HttpClient HTTP = EzClassifyContext.newHttpClient();

  private record Success(WireCorpus.Case recorded, String method, String template) {}

  private static List<Success> successes() throws Exception {
    WireCorpus corpus = WireCorpus.vendored();
    JsonObject raw =
        JsonParser.parseString(Files.readString(Path.of("../vendor/spec/wire/v1/corpus.json")))
            .getAsJsonObject();
    List<Success> out = new ArrayList<>();
    for (JsonElement item : raw.getAsJsonArray("cases")) {
      JsonObject entry = item.getAsJsonObject();
      String id = entry.get("id").getAsString();
      WireCorpus.Case c = corpus.cases.get(id);
      if (c.status() < 200 || c.status() >= 300) continue;
      if (WireCaseDrivers.DRIVERS.get(id) != WireCaseDrivers.Boundary.PUBLIC) continue;
      String template = entry.getAsJsonObject("request").get("pathTemplate").getAsString();
      out.add(new Success(c, c.method().toLowerCase(), template));
    }
    return out;
  }

  @Test
  void theCorpusRecordsASuccessForEveryOperationWithARequiredMember() throws Exception {
    long withMembers =
        successes().stream()
            .filter(
                s ->
                    !SPEC.requiredMembers(s.method(), s.template(), s.recorded().responseBody())
                        .isEmpty())
            .map(s -> s.method() + " " + s.template())
            .distinct()
            .count();
    // Every operation but readiness, which no public method sends.
    assertEquals(SPEC.operations().size() - 1, withMembers);
  }

  @TestFactory
  Stream<DynamicTest> aSuccessMissingARequiredMemberIsRefusedNamingIt() throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (Success success : successes()) {
      tests.add(
          DynamicTest.dynamicTest(success.recorded().id(), () -> refuseEachMutation(success)));
    }
    return tests.stream();
  }

  private static void refuseEachMutation(Success success) throws Exception {
    WireCorpus.Case c = success.recorded();
    List<OpenApiSchemas.RequiredMember> members =
        SPEC.requiredMembers(success.method(), success.template(), c.responseBody());
    int refused = 0;
    try (Replay replay = new Replay(c)) {
      // The recorded body itself is accepted, so each refusal below is the mutation's doing.
      replay.body.set(c.responseBody());
      call(replay, c);
      for (OpenApiSchemas.RequiredMember member : members) {
        List<JsonElement> mutations = new ArrayList<>();
        mutations.add(without(c.responseBody(), member, null));
        if (!member.nullable()) mutations.add(without(c.responseBody(), member, JsonNull.INSTANCE));
        for (JsonElement mutated : mutations) {
          // A union can accept the body through another alternative; then it is not malformed.
          if (SPEC.acceptsResponse(success.method(), success.template(), mutated)) continue;
          replay.body.set(mutated);
          MalformedResponseException error =
              assertThrows(
                  MalformedResponseException.class,
                  () -> call(replay, c),
                  c.id() + " without " + member.path());
          assertEquals(member.path(), error.path(), c.id() + ": " + error.getMessage());
          refused++;
        }
      }
    }
    assertEquals(members.isEmpty(), refused == 0, c.id());
  }

  /**
   * A refused answer is never kept: the next call reaches the server and returns its answer.
   * Process is a cached operation, so a kept refusal would be served from the cache instead.
   */
  @Test
  void aRefusedSuccessIsNotCached() throws Exception {
    WireCorpus.Case c = WireCorpus.vendored().cases.get("ctx-scheme-process.ok");
    JsonObject broken = c.responseBody().deepCopy().getAsJsonObject();
    broken.getAsJsonObject("result").remove("valid");
    try (Replay replay = new Replay(c)) {
      String[] pieces = c.target().split("/", 6);
      EzClassifyContext context =
          new EzClassifyContext(
              replay.url(),
              pieces[3],
              pieces[4],
              WireCaseDrivers.credentials(c),
              HTTP,
              ContextCacheOptions.defaults());
      replay.body.set(broken);
      MalformedResponseException error =
          assertThrows(MalformedResponseException.class, () -> WireCaseDrivers.invoke(c, context));
      assertEquals("$.result.valid", error.path());
      replay.body.set(c.responseBody());
      assertNotNull(WireCaseDrivers.invoke(c, context));
      assertEquals(2, replay.hits.get());
      WireCaseDrivers.invoke(c, context);
      assertEquals(2, replay.hits.get(), "the accepted answer is the one kept");
    }
  }

  @Test
  void aBodyThatIsNotJsonIsMalformedWithNoMemberPath() throws Exception {
    WireCorpus.Case c = WireCorpus.vendored().cases.get("health.ok");
    try (Replay replay = new Replay(c)) {
      replay.raw.set("not json");
      MalformedResponseException error =
          assertThrows(MalformedResponseException.class, () -> call(replay, c));
      assertEquals(0, error.statusCode());
      assertEquals(null, error.path());
      assertFalse(error.getMessage().contains("not json"), "the body is not echoed");
      assertTrue(error instanceof EzClassifyClientException);
    }
  }

  private static Object call(Replay replay, WireCorpus.Case c) throws Exception {
    String[] pieces = c.target().split("/", 6);
    if (pieces.length >= 5 && pieces[1].equals("v1") && pieces[2].equals("ctx")) {
      EzClassifyContext context =
          new EzClassifyContext(
              replay.url(),
              pieces[3],
              pieces[4],
              WireCaseDrivers.credentials(c),
              HTTP,
              new ContextCacheOptions(false, true));
      return WireCaseDrivers.invoke(c, context);
    }
    try (EzClassifyClient client =
        new EzClassifyClient(replay.url(), WireCaseDrivers.credentials(c))) {
      return WireCaseDrivers.invoke(c, client);
    }
  }

  /** A deep copy of {@code body} with one member removed, or replaced when a value is given. */
  private static JsonElement without(
      JsonElement body, OpenApiSchemas.RequiredMember member, JsonElement replacement) {
    JsonElement copy = body.deepCopy();
    JsonElement parent = copy;
    for (Object step : member.parent()) {
      parent =
          step instanceof Integer index
              ? parent.getAsJsonArray().get(index)
              : parent.getAsJsonObject().get((String) step);
    }
    JsonObject holder = parent.getAsJsonObject();
    if (replacement == null) holder.remove(member.name());
    else holder.add(member.name(), replacement);
    return copy;
  }

  /** A loopback server answering every request with the recorded success, or a substitute. */
  private static final class Replay implements AutoCloseable {
    final AtomicReference<JsonElement> body = new AtomicReference<>();
    final AtomicReference<String> raw = new AtomicReference<>();
    final AtomicInteger hits = new AtomicInteger();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newSingleThreadExecutor();

    Replay(WireCorpus.Case c) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            for (Map.Entry<String, JsonElement> header : c.responseHeaders().entrySet()) {
              exchange.getResponseHeaders().set(header.getKey(), header.getValue().getAsString());
            }
            String text = raw.get() != null ? raw.get() : body.get().toString();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(c.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
