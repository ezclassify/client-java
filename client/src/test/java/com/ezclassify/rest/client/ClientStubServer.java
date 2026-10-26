package com.ezclassify.rest.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-test HTTP stub server for {@link EzClassifyClient} unit tests.
 *
 * <p>Binds to an ephemeral port, serves per-path canned JSON responses, and records the most recent
 * request for assertion. Only the JDK built-in {@code com.sun.net.httpserver} is used: no
 * additional runtime dependencies.
 *
 * <p>Intended as a reusable fixture across test classes in this module. Sibling test classes can
 * construct their own instance with whichever route set they need, then close it in
 * {@code @AfterAll}.
 *
 * <pre>{@code
 * stub = new ClientStubServer(Map.of(
 *         "/v1/ctx", "{\"contexts\":[\"demo\"]}",
 *         "/v1/health", healthJson));
 * client = stub.newClient();
 * client.listContexts();
 * assertEquals("/v1/ctx", stub.lastRequest().path());
 * }</pre>
 */
@SuppressWarnings("restriction")
final class ClientStubServer implements Closeable {

  /** Snapshot of the most recent HTTP request the stub received. */
  record CapturedRequest(String method, String path, String query, String body) {}

  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor;
  private final Map<String, byte[]> routes;
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> extraHeaders = new ConcurrentHashMap<>();
  private final AtomicReference<CapturedRequest> lastRequest = new AtomicReference<>();
  private final AtomicInteger requestCount = new AtomicInteger();
  private final AtomicReference<String> resolvedVersionHeader;

  /**
   * Create and start a stub server that returns the given canned JSON responses.
   *
   * @param responses map of API path (e.g. {@code "/v1/ctx"}) to the JSON string to return for any
   *     request to that path
   */
  ClientStubServer(Map<String, String> responses) throws IOException {
    this(responses, null);
  }

  /**
   * Create and start a stub server that returns the given canned JSON responses, and includes the
   * given resolved-version value in every response via {@code X-EzClassify-Resolved-Version}. Pass
   * {@code null} to omit the header.
   *
   * <p>The header is required when the stub serves {@link EzClassifyContext}-bound requests (e.g.
   * {@code connect()} tests), where the client reads this header to populate {@link
   * EzClassifyContext#resolvedVersion()}.
   */
  ClientStubServer(Map<String, String> responses, String resolvedVersionHeader) throws IOException {
    this.resolvedVersionHeader = new AtomicReference<>(resolvedVersionHeader);
    routes = new ConcurrentHashMap<>();
    for (Map.Entry<String, String> e : responses.entrySet()) {
      routes.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
    }
    // Bound to the loopback address, like the other loopback servers in this suite: a wildcard bind
    // can share its port number with one of them, and a request meant for this stub then reaches
    // that server instead.
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newFixedThreadPool(2);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  /**
   * The smallest Context description the contract allows, for the Context a {@code
   * /v1/ctx/{id}/{version}} path names. Connecting reads it, so a stub that serves a Context needs
   * one; a test that is about the description builds its own.
   */
  static String description(String contextPath) {
    String[] segments = contextPath.substring("/v1/ctx/".length()).split("/");
    String id = segments[0];
    String version = segments.length > 1 ? segments[1] : "latest";
    String component =
        "{\"id\":\"" + id + "-scheme\",\"version\":\"1.0.0\",\"resolvedVersion\":\"1.0.0\"}";
    return "{\"id\":\""
        + id
        + "\",\"version\":\""
        + version
        + "\",\"scheme\":"
        + component
        + ",\"schemes\":[]}";
  }

  /** A complete health answer reporting the given engine version and build. */
  static String health(String engineVersion, String engineSha) {
    return "{\"status\":\"ok\",\"engineVersion\":\""
        + engineVersion
        + "\",\"engineSha\":\""
        + engineSha
        + "\",\"gitTag\":\"v"
        + engineVersion
        + "\",\"openapiVersion\":\""
        + engineVersion
        + "\",\"abiVersion\":\"v1\"}";
  }

  /** Base URL of the running stub, e.g. {@code http://127.0.0.1:54321}. */
  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Create an {@link EzClassifyClient} pointed at this stub. */
  EzClassifyClient newClient() {
    return new EzClassifyClient(url());
  }

  /**
   * Replace the canned response for one path while the stub is running, so a test can make the same
   * client observe a changing server.
   *
   * @param path API path to re-route
   * @param json JSON body to return for that path
   */
  void setResponse(String path, String json) {
    routes.put(path, json.getBytes(StandardCharsets.UTF_8));
  }

  /** Set a route's HTTP status while preserving its canned JSON body. */
  void setStatus(String path, int status) {
    statuses.put(path, status);
  }

  /** Add a response header to every answer on one route, replacing any earlier value for it. */
  void setHeader(String path, String name, String value) {
    extraHeaders.computeIfAbsent(path, key -> new ConcurrentHashMap<>()).put(name, value);
  }

  /** Change the resolved-version header for later requests from this test server. */
  void setResolvedVersionHeader(String version) {
    resolvedVersionHeader.set(version);
  }

  /**
   * The most recent request captured by the stub, or {@code null} if no request has arrived yet.
   */
  CapturedRequest lastRequest() {
    return lastRequest.get();
  }

  /** How many requests the stub has received since it started. */
  int requestCount() {
    return requestCount.get();
  }

  @Override
  public void close() {
    server.stop(0);
    // HttpServer.stop does not shut down a caller-supplied executor; without this every
    // fixture leaks its non-daemon pool threads until JVM exit.
    executor.shutdown();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
    requestCount.incrementAndGet();
    lastRequest.set(
        new CapturedRequest(
            exchange.getRequestMethod(),
            path,
            exchange.getRequestURI().getRawQuery(),
            new String(bodyBytes, StandardCharsets.UTF_8)));

    exchange.getResponseHeaders().set("Content-Type", "application/json");
    String resolved = resolvedVersionHeader.get();
    if (resolved != null) {
      exchange.getResponseHeaders().set("X-EzClassify-Resolved-Version", resolved);
    }
    extraHeaders
        .getOrDefault(path, Map.of())
        .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    byte[] resp = routes.get(path);
    if (resp == null) {
      byte[] err =
          ("{\"error\":\"no stub for path " + path + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(404, err.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(err);
      }
    } else {
      exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), resp.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp);
      }
    }
  }
}
