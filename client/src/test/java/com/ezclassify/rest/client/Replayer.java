package com.ezclassify.rest.client;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Stub HTTP server that replays pre-recorded engine responses during conformance tests.
 *
 * <p>Lookup algorithm: {@code sha256(normalizedPath + "|" + stableJson(body))}. Trailing slashes on
 * the path are stripped before hashing. JSON object keys in the request body are normalized to
 * lexicographic order before hashing, so key-order differences never produce a miss. A miss returns
 * HTTP 404 with a JSON envelope: {@code {error, message, path, bodyHash}}.
 *
 * <p>Uses only the JDK built-in {@code com.sun.net.httpserver.HttpServer}: no Spring, no Netty, no
 * additional runtime dependencies.
 */
@SuppressWarnings("restriction")
public final class Replayer implements Closeable {

  private final Map<String, byte[]> recordings;
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor;
  private volatile byte[] lastRequestBody;

  /**
   * Create and start a replayer seeded with the given recording map.
   *
   * @param recordings map from {@code sha256(normalizedPath+"|"+stableJson(body))} to the raw JSON
   *     response bytes to return on a hit
   */
  public Replayer(Map<String, byte[]> recordings) throws IOException {
    this.recordings = recordings;
    this.server = HttpServer.create(new InetSocketAddress(0), 0);
    this.executor = Executors.newFixedThreadPool(4);
    this.server.setExecutor(executor);
    this.server.createContext("/", this::handle);
    this.server.start();
  }

  /** Returns the base URL of the running server, e.g. {@code http://localhost:54321}. */
  public String url() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /** Returns the exact bytes received for the most recent request after its response completes. */
  byte[] lastRequestBody() {
    return lastRequestBody;
  }

  @Override
  public void close() {
    server.stop(0);
    // HttpServer.stop does not shut down a caller-supplied executor; without this every
    // fixture leaks its non-daemon pool threads until JVM exit.
    executor.shutdown();
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
    lastRequestBody = bodyBytes;
    String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8).trim();

    // Normalize path: strip one or more trailing slashes.
    String path = exchange.getRequestURI().getPath();
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }

    Object bodyObj;
    try {
      bodyObj = parseJson(bodyStr);
    } catch (Exception e) {
      byte[] resp =
          ("{\"error\":\"invalid_json\",\"message\":" + jsonEncodeString(e.getMessage()) + "}")
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(400, resp.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp);
      }
      return;
    }

    String stable = stableJson(bodyObj);
    String hash = sha256(path + "|" + stable);

    exchange.getResponseHeaders().set("Content-Type", "application/json");

    byte[] responseBody = recordings.get(hash);
    if (responseBody == null) {
      String bodyHash = sha256(stable);
      String miss =
          "{\"error\":\"no_recording\",\"message\":"
              + jsonEncodeString("no recording for path=" + path)
              + ",\"path\":"
              + jsonEncodeString(path)
              + ",\"bodyHash\":"
              + jsonEncodeString(bodyHash)
              + "}";
      byte[] resp = miss.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(404, resp.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp);
      }
    } else {
      exchange.sendResponseHeaders(200, responseBody.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody);
      }
    }
  }

  /**
   * Serialize {@code v} as compact JSON with all object keys sorted lexicographically. Arrays are
   * not reordered. This is the canonical form used for recording-key hashing.
   */
  public static String stableJson(Object v) {
    if (v == null) return "null";
    if (v instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) v;
      List<String> keys = new ArrayList<>(m.keySet());
      Collections.sort(keys);
      StringBuilder sb = new StringBuilder("{");
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(jsonEncodeString(keys.get(i)));
        sb.append(':');
        sb.append(stableJson(m.get(keys.get(i))));
      }
      sb.append('}');
      return sb.toString();
    }
    if (v instanceof List) {
      List<?> arr = (List<?>) v;
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < arr.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(stableJson(arr.get(i)));
      }
      sb.append(']');
      return sb.toString();
    }
    if (v instanceof String) {
      return jsonEncodeString((String) v);
    }
    if (v instanceof Boolean) {
      return ((Boolean) v) ? "true" : "false";
    }
    if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
      return v.toString();
    }
    if (v instanceof Double) {
      double d = (Double) v;
      if (!Double.isInfinite(d)
          && !Double.isNaN(d)
          && d == Math.floor(d)
          && d >= Long.MIN_VALUE
          && d <= Long.MAX_VALUE) {
        return Long.toString((long) d);
      }
      String s = Double.toString(d);
      return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
    if (v instanceof Float) {
      float f = (Float) v;
      if (!Float.isInfinite(f) && !Float.isNaN(f) && f == Math.floor(f)) {
        return Long.toString((long) f);
      }
      String s = Float.toString(f);
      return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
    if (v instanceof Number) {
      return v.toString();
    }
    // Fallback: treat as a string.
    return jsonEncodeString(v.toString());
  }

  /**
   * Compute the replayer lookup key for a given path and body object. Strips trailing slashes from
   * path, then returns {@code sha256(path+"|"+stableJson(body))}.
   */
  public static String recordingKey(String path, Object body) {
    String normPath = path;
    while (normPath.length() > 1 && normPath.endsWith("/")) {
      normPath = normPath.substring(0, normPath.length() - 1);
    }
    return sha256(normPath + "|" + stableJson(body));
  }

  /** SHA-256 of the UTF-8 encoding of {@code input}, returned as a lowercase hex string. */
  public static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Parse a JSON string into a plain Java object tree. Objects become {@code Map<String,Object>},
   * arrays become {@code List<Object>}.
   */
  static Object parseJson(String s) {
    if (s == null || s.isEmpty()) return null;
    char first = s.charAt(0);
    if (first == '{' || first == '[' || first == '"') {
      return Json.toJava(JsonParser.parseString(s));
    }
    if ("null".equals(s)) return null;
    if ("true".equals(s)) return Boolean.TRUE;
    if ("false".equals(s)) return Boolean.FALSE;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException ignored) {
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException ignored) {
    }
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException ignored) {
    }
    return s;
  }

  static String jsonEncodeString(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
