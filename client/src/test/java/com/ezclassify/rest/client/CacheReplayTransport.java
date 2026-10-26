package com.ezclassify.rest.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The counting replay transport the cache contract asks a port's harness to supply.
 *
 * <p>It answers each request with the recorded response of the corpus case the request matches and
 * counts requests per case. Matching takes the method, the path and the canonical body as recorded,
 * with the allowances CACHE-SEMANTICS.md gives: the {@code {version}} segment may carry the
 * binding's version, the version the case was recorded at, or a resolved version a plan scripted,
 * and query parameters match as a set. A binding that names no version sends none: its operations
 * carry no {@code {version}} segment at all, and only the Context description is asked for at
 * {@code latest}. It can hold a case's requests in flight until released, and can script the three
 * responses a recording cannot provide, each derived from recorded bytes.
 */
final class CacheReplayTransport implements AutoCloseable {
  /** What the transport sends back for one request. */
  record Response(int status, Map<String, String> headers, String body) {}

  private static final Pattern CTX_PATH = Pattern.compile("^(/v1/ctx/[^/?]+)/([^/?]+)(.*)$");
  private static final Pattern DESCRIBE_PATH = Pattern.compile("^/v1/ctx/[^/?]+/[^/?]+$");
  private static final Pattern DESCRIBE_LATEST_PATH = Pattern.compile("^/v1/ctx/[^/?]+/latest$");
  private static final Pattern UNVERSIONED_CTX_PATH = Pattern.compile("^(/v1/ctx/[^/?]+)/(.*)$");
  private static final String RESOLVED_HEADER = "x-ezclassify-resolved-version";
  private static final long WAIT_MILLIS = 10_000;

  /**
   * A scripted answer; a denial replays another case's response, which names no version to move.
   */
  private record Scripted(Function<WireCorpus.Case, Response> response, boolean movesVersion) {}

  private static final class Held {
    boolean released;
  }

  private final Map<WireCorpus.Key, List<WireCorpus.Case>> byRequest = new HashMap<>();
  private final Map<String, WireCorpus.Case> byId;
  private final HttpServer server;
  private final ExecutorService executor;
  private final Object lock = new Object();
  private final Map<String, Integer> counts = new HashMap<>();
  private final Set<String> gated = new HashSet<>();
  private final Map<String, List<Held>> held = new HashMap<>();
  private final Map<String, Deque<Scripted>> scripted = new HashMap<>();
  private final List<String> unmatched = new ArrayList<>();
  private final Set<String> versionSegments = new HashSet<>();
  private final String denialCase;
  private final boolean versionless;
  private String resolvedVersion;
  private boolean closed;

  /**
   * Start a transport over the corpus.
   *
   * @param corpus the recorded exchanges to replay
   * @param denialCase corpus case whose response a scripted denial replays
   * @param bindingVersion the version segment the client under test puts in its paths, or {@code
   *     null} when it names none
   */
  CacheReplayTransport(WireCorpus corpus, String denialCase, String bindingVersion)
      throws IOException {
    this.byId = corpus.cases;
    this.denialCase = denialCase;
    this.versionless = bindingVersion == null;
    // A versionless client still reads the Context description at latest.
    versionSegments.add(versionless ? "latest" : bindingVersion);
    for (WireCorpus.Case recorded : corpus.cases.values()) {
      var key = key(recorded.method(), recorded.target(), recorded.body(), recorded.hasBody());
      // Cases that differ only in the credential they were recorded with share one request.
      byRequest.computeIfAbsent(key, k -> new ArrayList<>()).add(recorded);
    }
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.executor =
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "cache-replay");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Forget the traffic so far, so a plan counts only what follows its client's connect. */
  void resetCounts() {
    synchronized (lock) {
      counts.clear();
    }
  }

  int count(String caseId) {
    synchronized (lock) {
      return counts.getOrDefault(caseId, 0);
    }
  }

  /** Requests no recorded case matched; a plan with any has measured something else. */
  List<String> unmatched() {
    synchronized (lock) {
      return List.copyOf(unmatched);
    }
  }

  /** Hold every later request matching the case in flight until {@link #release}. */
  void gate(String caseId) {
    synchronized (lock) {
      gated.add(caseId);
    }
  }

  /** Whether a request for the case has reached the transport and is being held. */
  void awaitHeld(String caseId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
    synchronized (lock) {
      while (held.getOrDefault(caseId, List.of()).isEmpty()) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new AssertionError("no request for " + caseId + " was held");
        lock.wait(remaining);
      }
    }
  }

  /** Answer the held requests for the case, and stop holding later ones. */
  void release(String caseId) {
    synchronized (lock) {
      gated.remove(caseId);
      for (Held request : held.getOrDefault(caseId, List.of())) request.released = true;
      held.remove(caseId);
      lock.notifyAll();
    }
  }

  /**
   * Answer the next request matching the case with its recorded response, the JSON pointers
   * removed. Each pointer names a member the contract requires.
   */
  void scriptMalformedSuccess(String caseId, List<String> removeMembers) {
    script(
        caseId,
        true,
        recorded -> {
          JsonElement body = JsonParser.parseString(recorded.responseBody().toString());
          for (String pointer : removeMembers) remove(body, pointer);
          return new Response(recorded.status(), headers(recorded), body.toString());
        });
  }

  /** Answer the next request matching the case with the denial case's recorded response. */
  void scriptDenial(String caseId) {
    script(
        caseId,
        false,
        recorded -> {
          WireCorpus.Case denial = byId.get(denialCase);
          return new Response(denial.status(), headers(denial), denial.responseBody().toString());
        });
  }

  /**
   * Make every later response name the version wherever the recorded bytes name the resolved
   * Context version: the resolved-version header, the resolution's {@code resolvedVersion}, and the
   * version of a Context description.
   */
  void scriptResolvedVersion(String version) {
    synchronized (lock) {
      resolvedVersion = version;
      versionSegments.add(version);
    }
  }

  private void script(
      String caseId, boolean movesVersion, Function<WireCorpus.Case, Response> response) {
    synchronized (lock) {
      scripted
          .computeIfAbsent(caseId, id -> new ArrayDeque<>())
          .add(new Scripted(response, movesVersion));
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      lock.notifyAll();
    }
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String raw = exchange.getRequestURI().getRawPath();
      if (exchange.getRequestURI().getRawQuery() != null)
        raw += "?" + exchange.getRequestURI().getRawQuery();
      byte[] content = exchange.getRequestBody().readAllBytes();
      boolean hasBody = content.length != 0;
      JsonElement body =
          hasBody ? JsonParser.parseString(new String(content, StandardCharsets.UTF_8)) : null;
      WireCorpus.Case recorded =
          match(
              exchange.getRequestMethod(),
              raw,
              body,
              hasBody,
              credentialClass(exchange.getRequestHeaders()));
      if (recorded == null) {
        synchronized (lock) {
          unmatched.add(exchange.getRequestMethod() + " " + raw + " " + body);
        }
        send(exchange, new Response(500, Map.of(), "{\"error\":\"unrecorded request\"}"));
        return;
      }
      holdIfGated(recorded.id());
      send(exchange, answer(recorded));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /** The credential class the corpus records requests under, read off the request's headers. */
  private static String credentialClass(Headers headers) {
    boolean bearer = headers.getFirst("Authorization") != null;
    boolean carrier = headers.getFirst("X-EzClassify-Context-Token") != null;
    return bearer ? "invalid-bearer" : carrier ? "context-token" : "none";
  }

  private WireCorpus.Case match(
      String method, String rawTarget, JsonElement body, boolean hasBody, String credential) {
    WireCorpus.Case recorded = null;
    for (WireCorpus.Case candidate :
        byRequest.getOrDefault(requestKey(method, rawTarget, body, hasBody), List.of())) {
      if (candidate.credential().equals(credential)) recorded = candidate;
    }
    if (recorded == null) return null;
    synchronized (lock) {
      counts.merge(recorded.id(), 1, Integer::sum);
      String segment = requestVersionSegment(rawTarget);
      if (segment != null
          && !versionSegments.contains(segment)
          && !segment.equals(versionSegment(recorded.target()))) {
        counts.merge(recorded.id(), -1, Integer::sum);
        return null;
      }
    }
    return recorded;
  }

  private void holdIfGated(String caseId) throws InterruptedException {
    synchronized (lock) {
      if (!gated.contains(caseId)) return;
      Held request = new Held();
      held.computeIfAbsent(caseId, id -> new ArrayList<>()).add(request);
      lock.notifyAll();
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3 * WAIT_MILLIS);
      while (!request.released && !closed) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new AssertionError("held request for " + caseId + " expired");
        lock.wait(remaining);
      }
    }
  }

  private Response answer(WireCorpus.Case recorded) {
    Scripted next = null;
    String version;
    synchronized (lock) {
      Deque<Scripted> queue = scripted.get(recorded.id());
      if (queue != null) next = queue.poll();
      version = resolvedVersion;
    }
    Response response =
        next != null
            ? next.response().apply(recorded)
            : new Response(
                recorded.status(), headers(recorded), recorded.responseBody().toString());
    boolean moves = next == null || next.movesVersion();
    return version == null || !moves ? response : withResolvedVersion(response, recorded, version);
  }

  private static Response withResolvedVersion(
      Response response, WireCorpus.Case recorded, String version) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (var header : response.headers().entrySet())
      headers.put(
          header.getKey(),
          RESOLVED_HEADER.equalsIgnoreCase(header.getKey()) ? version : header.getValue());
    JsonElement body = JsonParser.parseString(response.body());
    if (body.isJsonObject()) {
      JsonObject object = body.getAsJsonObject();
      JsonElement resolution = object.get("resolution");
      if (resolution != null
          && resolution.isJsonObject()
          && resolution.getAsJsonObject().has("resolvedVersion"))
        resolution.getAsJsonObject().addProperty("resolvedVersion", version);
      if ("GET".equals(recorded.method()) && DESCRIBE_PATH.matcher(recorded.target()).matches())
        object.addProperty("version", version);
    }
    return new Response(response.status(), headers, body.toString());
  }

  private static Map<String, String> headers(WireCorpus.Case recorded) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (var header : recorded.responseHeaders().entrySet())
      headers.put(header.getKey(), header.getValue().getAsString());
    return headers;
  }

  private static void send(HttpExchange exchange, Response response) throws IOException {
    for (var header : response.headers().entrySet())
      exchange.getResponseHeaders().set(header.getKey(), header.getValue());
    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(response.status(), bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  /** Drop the member a JSON pointer names; the member must exist, or the plan removes nothing. */
  private static void remove(JsonElement root, String pointer) {
    String[] path = pointer.substring(1).split("/");
    JsonElement at = root;
    for (int i = 0; i < path.length - 1; i++) at = at.getAsJsonObject().get(path[i]);
    WireCorpus.require(
        at.getAsJsonObject().remove(path[path.length - 1]) != null,
        "recorded response has no " + pointer);
  }

  /**
   * A request's identity. A versionless client sends no segment to mask, so the placeholder is
   * inserted where the recorded paths carry one; the Context description, the one request such a
   * client sends at {@code latest}, is masked like any other.
   */
  private WireCorpus.Key requestKey(
      String method, String target, JsonElement body, boolean hasBody) {
    if (versionless && !DESCRIBE_LATEST_PATH.matcher(target).matches()) {
      Matcher matcher = UNVERSIONED_CTX_PATH.matcher(target);
      if (matcher.matches())
        return WireCorpus.key(
            method, matcher.group(1) + "/{version}/" + matcher.group(2), body, hasBody);
    }
    return key(method, target, body, hasBody);
  }

  /** The version segment a request carries, none for a versionless client's operations. */
  private String requestVersionSegment(String target) {
    return versionless && !DESCRIBE_LATEST_PATH.matcher(target).matches()
        ? null
        : versionSegment(target);
  }

  /** The request's identity with the {@code {version}} segment masked. */
  private static WireCorpus.Key key(
      String method, String target, JsonElement body, boolean hasBody) {
    return WireCorpus.key(method, maskVersion(target), body, hasBody);
  }

  private static String maskVersion(String target) {
    Matcher matcher = CTX_PATH.matcher(target);
    return matcher.matches() ? matcher.group(1) + "/{version}" + matcher.group(3) : target;
  }

  private static String versionSegment(String target) {
    Matcher matcher = CTX_PATH.matcher(target);
    return matcher.matches() ? matcher.group(2) : null;
  }
}
