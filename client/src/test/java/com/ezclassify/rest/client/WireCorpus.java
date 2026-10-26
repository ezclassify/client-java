package com.ezclassify.rest.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Test-only recorded transport boundary, with no sibling-repository fallback. */
final class WireCorpus {
  record Case(
      String id,
      String method,
      String target,
      JsonElement body,
      boolean hasBody,
      JsonObject requestHeaders,
      String credential,
      int status,
      JsonElement responseBody,
      JsonObject responseHeaders) {}

  final Map<String, Case> cases;

  WireCorpus(Map<String, Case> cases) {
    this.cases = Map.copyOf(cases);
  }

  void requireIds(java.util.Set<String> ids) {
    require(cases.keySet().equals(ids), "missing or unknown wire driver ids");
  }

  static WireCorpus vendored() throws Exception {
    Path vendor = Path.of("../vendor/spec");
    return load(vendor.resolve("wire/v1"), vendor.resolve("openapi.yaml"));
  }

  static WireCorpus load(Path directory, Path openapi) throws Exception {
    JsonObject corpus =
        JsonParser.parseString(Files.readString(directory.resolve("corpus.json")))
            .getAsJsonObject();
    JsonObject manifest =
        JsonParser.parseString(Files.readString(directory.resolve("manifest.json")))
            .getAsJsonObject();
    require(
        "v1".equals(corpus.get("corpusVersion").getAsString())
            && "v1".equals(manifest.get("corpusVersion").getAsString()),
        "unsupported corpus");
    String digest =
        java.util.HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(openapi)));
    require(
        ("sha256:" + digest).equals(manifest.getAsJsonObject("spec").get("digest").getAsString()),
        "wire/OpenAPI provenance mismatch");
    require(
        manifest.getAsJsonArray("contractDivergences").isEmpty(), "recorded contract divergences");
    Map<String, Case> cases = new LinkedHashMap<>();
    for (JsonElement item : corpus.getAsJsonArray("cases")) {
      JsonObject entry = item.getAsJsonObject();
      JsonObject request = entry.getAsJsonObject("request"),
          response = entry.getAsJsonObject("response");
      String id = entry.get("id").getAsString();
      Case c =
          new Case(
              id,
              request.get("method").getAsString(),
              request.get("path").getAsString(),
              request.get("body"),
              request.has("body"),
              request.getAsJsonObject("headers"),
              entry.getAsJsonObject("auth").get("credential").getAsString(),
              response.get("status").getAsInt(),
              response.get("body"),
              response.getAsJsonObject("headers"));
      require(cases.put(id, c) == null, "duplicate case id: " + id);
    }
    require(
        !cases.isEmpty()
            && manifest.getAsJsonObject("coverage").get("cases").getAsInt() == cases.size(),
        "manifest case count mismatch");
    return new WireCorpus(cases);
  }

  static String target(String raw) {
    int split = raw.indexOf('?');
    String path = split < 0 ? raw : raw.substring(0, split);
    String query = split < 0 ? "" : raw.substring(split + 1);
    if (path.isEmpty()) path = "/";
    else if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
    if (query.isEmpty()) return path;
    List<String[]> pairs = new ArrayList<>();
    for (String pair : query.split("&", -1)) {
      if (pair.isEmpty()) continue;
      int at = pair.indexOf('=');
      pairs.add(
          at < 0
              ? new String[] {pair, ""}
              : new String[] {pair.substring(0, at), pair.substring(at + 1)});
    }
    pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
    List<String> encoded = new ArrayList<>();
    for (String[] pair : pairs) encoded.add(pair[0] + (pair[1].isEmpty() ? "" : "=" + pair[1]));
    return path + "?" + String.join("&", encoded);
  }

  // Gson tree equality is JSON object-order independent and array-order sensitive.
  // Absent bodies remain distinct from the JSON null tree.
  record Key(String method, String target, JsonElement body, boolean hasBody) {}

  static Key key(String method, String target, JsonElement body, boolean hasBody) {
    return new Key(method.toUpperCase(Locale.ROOT), target(target), body, hasBody);
  }

  static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static final class Replay {
    private final Case expected;
    int hits;

    Replay(Case expected) {
      this.expected = expected;
    }

    synchronized Case answer(
        String method, String target, byte[] content, Map<String, List<String>> headers) {
      boolean hasBody = content.length != 0;
      JsonElement body =
          hasBody
              ? JsonParser.parseString(new String(content, java.nio.charset.StandardCharsets.UTF_8))
              : null;
      require(hits == 0, expected.id() + ": repeated request");
      require(
          key(expected.method(), expected.target(), expected.body(), expected.hasBody())
              .equals(key(method, target, body, hasBody)),
          expected.id() + ": changed method/target/body");
      Map<String, List<String>> lower = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      lower.putAll(headers);
      for (var header : expected.requestHeaders().entrySet())
        require(
            List.of(header.getValue().getAsString()).equals(lower.get(header.getKey())),
            expected.id() + ": changed " + header.getKey());
      String bearer = first(lower, "authorization"),
          carrier = first(lower, "x-ezclassify-context-token");
      require(
          !lower.containsKey("authorization") || lower.get("authorization").size() == 1,
          "duplicate bearer");
      require(
          !lower.containsKey("x-ezclassify-context-token")
              || lower.get("x-ezclassify-context-token").size() == 1,
          "duplicate carrier");
      boolean contextPath = expected.target().startsWith("/v1/ctx");
      switch (expected.credential()) {
        case "context-token" -> {
          require(bearer == null, "unexpected bearer");
          require(
              contextPath ? "fixture-context-carrier".equals(carrier) : carrier == null,
              "wrong carrier");
        }
        case "invalid-bearer" -> {
          require("Bearer fixture-refused-bearer".equals(bearer), "wrong bearer");
          require(
              contextPath ? "fixture-context-carrier".equals(carrier) : carrier == null,
              "wrong carrier");
        }
        case "none" -> require(bearer == null && carrier == null, "leaked credential");
        default -> throw new AssertionError("unknown credential class");
      }
      hits++;
      return expected;
    }

    private static String first(Map<String, List<String>> headers, String name) {
      var values = headers.get(name);
      return values == null || values.isEmpty() ? null : values.get(0);
    }
  }
}
