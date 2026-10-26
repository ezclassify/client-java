package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Exact wire recordings exercise real sockets, without computing any semantic answers. */
class WireCorpusTest {
  private static WireCorpus corpus() throws Exception {
    return WireCorpus.vendored();
  }

  @Test
  void exactLiteralCoverage() throws Exception {
    var corpus = corpus();
    corpus.requireIds(WireCaseDrivers.DRIVERS.keySet());
    assertEquals(119, corpus.cases.size());
    assertEquals(
        82,
        WireCaseDrivers.DRIVERS.values().stream()
            .filter(v -> v == WireCaseDrivers.Boundary.PUBLIC)
            .count());
    assertEquals(37, WireCaseDrivers.PRIVATE_REASONS.size());
    assertEquals(
        WireCaseDrivers.PRIVATE_REASONS.keySet(),
        WireCaseDrivers.DRIVERS.entrySet().stream()
            .filter(e -> e.getValue() == WireCaseDrivers.Boundary.PRIVATE)
            .map(java.util.Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @TestFactory
  Stream<DynamicTest> everyLiteralCaseOverNativeHttp() throws Exception {
    var corpus = corpus();
    return WireCaseDrivers.DRIVERS.keySet().stream()
        .sorted()
        .map(id -> DynamicTest.dynamicTest(id, () -> run(corpus.cases.get(id))));
  }

  static void auditDefaultControl(WireCorpus.Case c) throws Exception {
    run(c, true);
  }

  private static void run(WireCorpus.Case c) throws Exception {
    run(c, false);
  }

  private static void run(WireCorpus.Case c, boolean auditDefault) throws Exception {
    var replay = new WireCorpus.Replay(c);
    List<Throwable> handlerFailures = new java.util.concurrent.CopyOnWriteArrayList<>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newSingleThreadExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try {
            String raw = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null)
              raw += "?" + exchange.getRequestURI().getRawQuery();
            var selected =
                replay.answer(
                    exchange.getRequestMethod(),
                    raw,
                    exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders());
            for (var header : selected.responseHeaders().entrySet())
              exchange.getResponseHeaders().set(header.getKey(), header.getValue().getAsString());
            byte[] bytes = selected.responseBody().toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(selected.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Throwable error) {
            handlerFailures.add(error);
            byte[] bytes = "{\"error\":\"unrecorded request\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    var observed = new ObservedHttpClient();
    try {
      Object value = null;
      Throwable thrown = null;
      EzClassifyContext context = null;
      String[] pieces = c.target().split("/", 6);
      try {
        if (pieces.length >= 5 && pieces[1].equals("v1") && pieces[2].equals("ctx")) {
          context =
              new EzClassifyContext(
                  base,
                  pieces[3],
                  pieces[4],
                  WireCaseDrivers.credentials(c),
                  observed,
                  new ContextCacheOptions(false, true));
          value = auditDefault ? context.document().audit("") : WireCaseDrivers.invoke(c, context);
        } else {
          try (var client = new EzClassifyClient(base, WireCaseDrivers.credentials(c))) {
            // An existing private pool seam; no SDK transport API is introduced for tests.
            var pool = EzClassifyClient.class.getDeclaredField("httpClient");
            pool.setAccessible(true);
            pool.set(client, observed);
            value = WireCaseDrivers.invoke(c, client);
          }
        }
      } catch (Throwable error) {
        thrown = error;
      }
      assertEquals(List.of(), handlerFailures, c.id());
      assertEquals(1, replay.hits, c.id());
      assertEquals(1, observed.responses.size(), c.id());
      var response = observed.responses.get(0);
      assertEquals(c.status(), response.statusCode(), c.id());
      assertEquals(c.responseBody(), JsonParser.parseString((String) response.body()), c.id());
      for (var header : c.responseHeaders().entrySet())
        assertEquals(
            List.of(header.getValue().getAsString()),
            response.headers().allValues(header.getKey()),
            c.id() + ": " + header.getKey());
      if (c.status() >= 400) {
        assertInstanceOf(EzClassifyClientException.class, thrown, c.id());
        var error = (EzClassifyClientException) thrown;
        assertEquals(c.status(), error.statusCode(), c.id());
        var body = c.responseBody().getAsJsonObject();
        assertEquals(optional(body, "code"), error.code(), c.id());
        assertEquals(optional(body, "detail"), error.detail(), c.id());
        if (body.has("error"))
          assertEquals(body.get("error").getAsString(), error.getMessage(), c.id());
      } else {
        assertNull(thrown, c.id());
        if (c.id().startsWith("ctx-describe."))
          // The typed description reads an omitted optional list as empty; every other member,
          // including each declared and resolved component version, must match the wire.
          assertEquals(
              withoutEmptyMembers(c.responseBody()), withoutEmptyMembers(WireJson.toTree(value)));
        else if (c.id().equals("ctx-list.ok"))
          assertEquals(c.responseBody().getAsJsonObject().get("contexts"), WireJson.toTree(value));
        else if (c.id().equals("ctx-versions.ok"))
          assertEquals(c.responseBody().getAsJsonObject().get("versions"), WireJson.toTree(value));
        else if (value instanceof PingResult ping) {
          var body = c.responseBody().getAsJsonObject();
          assertEquals(body.get("engineVersion").getAsString(), ping.engineVersion());
          assertEquals(body.get("engineSha").getAsString(), ping.engineSha());
          assertEquals(body.get("gitTag").getAsString(), ping.gitTag());
        } else if (isSchemeResource(c.target())) {
          // A bare resource has no envelope and no Java projection, so the typed answer must carry
          // everything the server sent: a dropped member fails here, not only an invented one.
          assertEquals(c.responseBody(), WireJson.toTree(value), c.id());
        } else if (c.target().endsWith("/access/rules")) {
          subset(c.responseBody().getAsJsonObject().get("rules"), WireJson.toTree(value), c.id());
        } else {
          assertNotNull(value, c.id());
          subset(c.responseBody(), WireJson.toTree(value), c.id());
        }
      }
      if (context != null && c.responseHeaders().has("x-ezclassify-resolved-version"))
        assertEquals(
            c.responseHeaders().get("x-ezclassify-resolved-version").getAsString(),
            context.resolvedVersion(),
            c.id());
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  /** A copy of a JSON tree without its null members and empty arrays, at every depth. */
  private static JsonElement withoutEmptyMembers(JsonElement value) {
    if (value.isJsonObject()) {
      JsonObject kept = new JsonObject();
      for (var member : value.getAsJsonObject().entrySet()) {
        JsonElement child = member.getValue();
        if (child.isJsonNull() || (child.isJsonArray() && child.getAsJsonArray().isEmpty()))
          continue;
        kept.add(member.getKey(), withoutEmptyMembers(child));
      }
      return kept;
    }
    if (value.isJsonArray()) {
      com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
      for (JsonElement child : value.getAsJsonArray()) kept.add(withoutEmptyMembers(child));
      return kept;
    }
    return value;
  }

  private static final java.util.regex.Pattern SCHEME_RESOURCE =
      java.util.regex.Pattern.compile(
          "^/v1/ctx/[^/]+/[^/]+/scheme(/(concepts|categories|formats|grammar|scopes|label-presets))?$");

  /** Whether a path is one of the scheme resource reads, which answer without an envelope. */
  private static boolean isSchemeResource(String target) {
    return SCHEME_RESOURCE.matcher(target).matches();
  }

  // Java deliberately projects some responses; compare every field it actually exposes.
  // The independent observed transport above compares the ENTIRE answer and all recorded headers.
  private static void subset(JsonElement expected, JsonElement actual, String path) {
    assertNotNull(actual, path);
    if (actual.isJsonObject()) {
      assertTrue(expected.isJsonObject(), path);
      for (var field : actual.getAsJsonObject().entrySet()) {
        assertTrue(expected.getAsJsonObject().has(field.getKey()), path + "." + field.getKey());
        subset(
            expected.getAsJsonObject().get(field.getKey()),
            field.getValue(),
            path + "." + field.getKey());
      }
    } else if (actual.isJsonArray()) {
      assertTrue(expected.isJsonArray(), path);
      assertEquals(expected.getAsJsonArray().size(), actual.getAsJsonArray().size(), path);
      for (int i = 0; i < actual.getAsJsonArray().size(); i++)
        subset(
            expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i), path + "[" + i + "]");
    } else assertEquals(expected, actual, path);
  }

  private static String optional(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsString()
        : null;
  }

  static final class ObservedHttpClient extends HttpClient {
    private final HttpClient delegate = HttpClient.newHttpClient();
    final List<HttpResponse<?>> responses = new ArrayList<>();

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      var response = delegate.send(request, handler);
      responses.add(response);
      return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      return delegate
          .sendAsync(request, handler)
          .thenApply(
              response -> {
                responses.add(response);
                return response;
              });
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      return delegate
          .sendAsync(request, handler, push)
          .thenApply(
              response -> {
                responses.add(response);
                return response;
              });
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return delegate.authenticator();
    }

    @Override
    public Version version() {
      return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
      return delegate.executor();
    }
  }
}
