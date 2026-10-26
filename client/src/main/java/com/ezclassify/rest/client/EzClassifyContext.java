package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.AccessRuleDescription;
import com.ezclassify.rest.contract.AssessmentLimitation;
import com.ezclassify.rest.contract.ContextOperationResult;
import com.ezclassify.rest.contract.ContextResolution;
import com.ezclassify.rest.contract.CtxComponentDescriptor;
import com.ezclassify.rest.contract.CtxDescribeResponseBody;
import com.ezclassify.rest.contract.DocumentResponseProjection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A handle bound to one named, versioned EzClassify {@code Context}. Every operation routes through
 * {@code /v1/ctx/{contextId}/{version}/*}: the path selects the composed bundle (scheme + layers +
 * guides + access policy), so request bodies carry payload only (no {@code schemeId}/{@code
 * guideId} selectors). A binding that names no version routes through {@code /v1/ctx/{contextId}/*}
 * instead and the server chooses: the Context token's default version when one is configured,
 * otherwise the newest published version. Like {@code latest}, that binding is unpinned.
 *
 * <p>Obtain one with {@link EzClassifyClient#connect(String, String, String)}, then reach the
 * operations through {@link #scheme()}, {@link #guide()}, {@link #access()}, {@link #crosswalk()},
 * and {@link #document()}:
 *
 * <pre>{@code
 * EzClassifyContext ctx = EzClassifyClient.connect("http://localhost:8080", "my-ctx", "1.0");
 * var response = ctx.scheme().process("MARKING");
 * ProcessResult r = response.result();
 * String resolved = ctx.resolvedVersion(); // concrete version the server resolved
 * }</pre>
 */
public final class EzClassifyContext {

  /** Response header carrying the concrete version the server resolved a ctx request to. */
  static final String RESOLVED_VERSION_HEADER = "X-EzClassify-Resolved-Version";

  /** Ceiling on establishing the TCP and TLS connection, separate from the per-request ceiling. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Ceiling on a single Context call, from sending the request to reading the last byte of the
   * response. The JDK bounds none of that by default, so without it an unresponsive endpoint, or
   * one that stalls partway through a body, would hang a Context call forever.
   */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final Set<String> CACHED_POST_OPERATIONS =
      Set.of(
          "scheme/process",
          "scheme/validate",
          "scheme/explain",
          "scheme/normalize",
          "scheme/render",
          "scheme/diff",
          "scheme/aggregate",
          "scheme/apply-preset",
          "scheme/suggest",
          "scheme/suggest-for-categories",
          "scheme/scan");

  /**
   * Whether an operation's result may be an empty JSON object. Almost every operation answers with
   * a populated object, so an empty one means a truncated or non-conforming response and is
   * refused. An operation whose answer is a map keyed by what the request named legitimately
   * answers {@code {}} when the request named nothing.
   */
  enum EmptyResult {
    REFUSED,
    ALLOWED
  }

  private final String baseUrl;
  private final String contextId;
  private final String version;
  private final HttpClient httpClient;
  private final RestClientCredentials credentials;
  private final ContextCacheOptions cacheOptions;
  private final ResponseCache responseCache;
  private final Duration requestTimeout;
  private final Object describeLock = new Object();
  private volatile String resolvedVersion;
  private volatile CtxDescribeResponseBody description;

  EzClassifyContext(
      String baseUrl, String contextId, String version, RestClientCredentials credentials) {
    this(baseUrl, contextId, version, credentials, newHttpClient(), ContextCacheOptions.defaults());
  }

  /**
   * Bind a handle that reuses an existing HTTP client. A JDK {@code HttpClient} owns a connection
   * pool and its own executor threads, so a host that holds one server client and many Context
   * handles must not end up with one pool per handle.
   */
  EzClassifyContext(
      String baseUrl,
      String contextId,
      String version,
      RestClientCredentials credentials,
      HttpClient httpClient) {
    this(baseUrl, contextId, version, credentials, httpClient, ContextCacheOptions.defaults());
  }

  EzClassifyContext(
      String baseUrl,
      String contextId,
      String version,
      RestClientCredentials credentials,
      HttpClient httpClient,
      ContextCacheOptions cacheOptions) {
    this(
        baseUrl,
        contextId,
        version,
        credentials,
        httpClient,
        cacheOptions,
        new ResponseCache(),
        REQUEST_TIMEOUT);
  }

  /**
   * As above, over a response cache and request deadline the caller supplies. Tests use it to move
   * the clock and to reach a deadline without waiting the full {@link #REQUEST_TIMEOUT}.
   */
  EzClassifyContext(
      String baseUrl,
      String contextId,
      String version,
      RestClientCredentials credentials,
      HttpClient httpClient,
      ContextCacheOptions cacheOptions,
      ResponseCache responseCache,
      Duration requestTimeout) {
    this.requestTimeout = ClientHttp.requireTimeout(requestTimeout);
    this.responseCache = responseCache;
    this.credentials = credentials == null ? RestClientCredentials.none() : credentials;
    this.baseUrl = Endpoints.requireTransport(baseUrl, this.credentials.present());
    this.contextId = contextId;
    // An empty version is how a configuration says it names none, the same as null.
    this.version = version == null || version.isBlank() ? null : version;
    this.cacheOptions = cacheOptions == null ? ContextCacheOptions.defaults() : cacheOptions;
    if (this.cacheOptions.cacheAccessDecisions() && !pinned()) {
      throw new IllegalArgumentException(
          "access decision caching requires a concrete Context version");
    }
    this.httpClient = httpClient;
  }

  /**
   * The client shape every EzClassify transport uses: a bounded connect phase, JDK defaults else.
   */
  static HttpClient newHttpClient() {
    return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  /** The HTTP client this handle sends through. Package-private: pool sharing is an internal. */
  HttpClient transport() {
    return httpClient;
  }

  /**
   * The context identifier this handle is bound to.
   *
   * @return the context id
   */
  public String contextId() {
    return contextId;
  }

  /**
   * The version segment used in request paths: a concrete version, {@code "latest"}, or {@code
   * null} when the binding names none, request paths carry no version segment, and the server
   * chooses.
   *
   * @return the version segment used in request paths, or {@code null} when the server chooses
   */
  public String version() {
    return version;
  }

  /**
   * Whether the binding names a published, immutable Context version. {@code latest} and a version
   * the server chooses can move under the client.
   */
  private boolean pinned() {
    return version != null && !"latest".equals(version);
  }

  /**
   * The concrete version the rest-api last resolved this context to, read from the {@value
   * #RESOLVED_VERSION_HEADER} response header. Populated by {@link #resolve()} (which {@link
   * EzClassifyClient#connect} runs) and refreshed when a subsequent server response supplies the
   * header. Cached operations do not contact the server. {@code null} until a response supplies it.
   *
   * @return the concrete version the server last resolved this context to, or {@code null} while no
   *     response has supplied the header
   */
  public String resolvedVersion() {
    return resolvedVersion;
  }

  /** Discard operation answers while keeping this Context binding and connection pool. */
  public void clearCache() {
    responseCache.clear();
  }

  /**
   * Scheme-domain operations bound to this context.
   *
   * @return a handle for the scheme-domain operations on this context
   */
  public ContextScheme scheme() {
    return new ContextScheme(this);
  }

  /**
   * Guide operations bound to this context.
   *
   * @return a handle for the guide-domain operations on this context
   */
  public ContextGuide guide() {
    return new ContextGuide(this);
  }

  /**
   * Access-control operations bound to this context.
   *
   * @return a handle for the access-control operations on this context
   */
  public ContextAccess access() {
    return new ContextAccess(this);
  }

  /**
   * Document-level operations bound to this context.
   *
   * @return a handle for the document-level operations on this context
   */
  public ContextDocument document() {
    return new ContextDocument(this);
  }

  /**
   * Directional crosswalk operations bound to this context.
   *
   * @return a handle for crosswalk operations declared by this context
   */
  public ContextCrosswalk crosswalk() {
    return new ContextCrosswalk(this);
  }

  /**
   * Validate the context binding against the server and populate {@link #resolvedVersion()}. Reads
   * the Context description and holds it for {@link #describeContext()}.
   *
   * @throws EzClassifyClientException if the server refuses the binding or cannot be reached
   * @throws MalformedResponseException if the description lacks a member the contract requires
   */
  public void resolve() {
    synchronized (describeLock) {
      description = parseDescription(sendCtx("GET", "", null));
    }
  }

  /**
   * Describe what this Context is made of: its scheme and layers, guides, access policy, and
   * crosswalks, each with the version the Context declares and the concrete version that answered,
   * plus every scheme it holds and the selector that addresses it. Record these versions with a
   * decision to show later which artifacts produced it.
   *
   * <p>The description is connection state, read once when the handle connects and held. It is not
   * a response-cache entry: {@link #clearCache()} leaves it in place and it has no lifetime. Under
   * a concrete version the composition cannot change, so the held description stays correct. Under
   * {@code latest} it is read again the first time it is asked for after a response reports a
   * different {@linkplain #resolvedVersion() resolved version}.
   *
   * <p>Optional components the Context does not declare ({@code guides}, {@code crosswalks}, and
   * nested {@code layers}) read as empty lists, and an undeclared {@code access} policy as {@code
   * null}.
   *
   * @return the Context description at the version this handle last resolved
   * @throws EzClassifyClientException if the server refuses the request, cannot be reached, or
   *     answers a description missing a required member
   */
  public CtxDescribeResponseBody describeContext() {
    synchronized (describeLock) {
      CtxDescribeResponseBody held = description;
      // The version check is what drops a description when an unpinned binding moves: the move
      // changes resolvedVersion, so the held copy no longer names it.
      String current = resolvedVersion;
      if (held != null && (current == null || held.version().equals(current))) return held;
      return fetchDescription();
    }
  }

  private CtxDescribeResponseBody fetchDescription() {
    CtxDescribeResponseBody fetched = parseDescription(sendCtx("GET", "", null));
    description = fetched;
    return fetched;
  }

  private static CtxDescribeResponseBody parseDescription(String raw) {
    CtxDescribeResponseBody wire;
    try {
      wire = WireJson.fromTree(Json.parseObject(raw), CtxDescribeResponseBody.class);
    } catch (JsonParseException e) {
      throw malformed(e);
    }
    return new CtxDescribeResponseBody(
        wire.id(),
        wire.version(),
        wire.description(),
        withLayers(wire.scheme()),
        List.copyOf(wire.schemes()),
        componentList(wire.guides()),
        wire.access() == null ? null : withLayers(wire.access()),
        componentList(wire.crosswalks()));
  }

  private static List<CtxComponentDescriptor> componentList(List<CtxComponentDescriptor> wire) {
    return wire == null ? List.of() : wire.stream().map(EzClassifyContext::withLayers).toList();
  }

  private static CtxComponentDescriptor withLayers(CtxComponentDescriptor component) {
    return new CtxComponentDescriptor(
        component.id(),
        component.version(),
        component.resolvedVersion(),
        componentList(component.layers()));
  }

  // --- transport ---------------------------------------------------------

  JsonObject postJson(String op, JsonObject body) {
    return Json.parseObject(sendCtx("POST", op, body.toString()));
  }

  <T> ContextOperationResult<T> postResult(
      String op, JsonObject body, Function<JsonElement, T> resultParser) {
    return postResult(op, body, resultParser, EmptyResult.REFUSED);
  }

  <T> ContextOperationResult<T> postResult(
      String op, JsonObject body, Function<JsonElement, T> resultParser, EmptyResult empty) {
    if (cacheOptions.disableCache() || !cacheablePost(op)) {
      return parseResult(postJson(op, body), resultParser, empty);
    }
    String key = cacheKey("POST", op, body);
    String response =
        responseCache.get(
            key,
            ttlNanos(op),
            () -> {
              String raw = sendCtx("POST", op, body.toString(), key);
              parseResult(Json.parseObject(raw), resultParser, empty);
              return raw;
            });
    return parseResult(Json.parseObject(response), resultParser, empty);
  }

  <T> ContextOperationResult<T> getResult(
      String opWithQuery, Function<JsonElement, T> resultParser) {
    if (cacheOptions.disableCache() || !opWithQuery.startsWith("scheme/describe-concept?")) {
      return parseResult(getJson(opWithQuery), resultParser, EmptyResult.REFUSED);
    }
    String key = cacheKey("GET", opWithQuery, null);
    String response =
        responseCache.get(
            key,
            ttlNanos("scheme/describe-concept"),
            () -> {
              String raw = sendCtx("GET", opWithQuery, null, key);
              parseResult(Json.parseObject(raw), resultParser, EmptyResult.REFUSED);
              return raw;
            });
    return parseResult(Json.parseObject(response), resultParser, EmptyResult.REFUSED);
  }

  private static <T> ContextOperationResult<T> parseResult(
      JsonObject envelope, Function<JsonElement, T> resultParser, EmptyResult empty) {
    try {
      ContextResolution resolution =
          WireJson.fromTree(requiredObject(envelope, "resolution"), ContextResolution.class);
      List<AssessmentLimitation> limitations =
          WireJson.listFromTree(envelope.get("operationLimitations"), AssessmentLimitation.class);
      DocumentResponseProjection projection =
          envelope.has("responseProjection") && !envelope.get("responseProjection").isJsonNull()
              ? WireJson.fromTree(
                  envelope.get("responseProjection"), DocumentResponseProjection.class)
              : null;
      return new ContextOperationResult<>(
          resolution, resultParser.apply(requiredResult(envelope, empty)), limitations, projection);
    } catch (JsonParseException e) {
      throw malformed(e);
    }
  }

  /**
   * Gson reports a response that does not match the contract as a {@link JsonParseException}. A
   * caller sees one exception type for every malformed response, and the cache keeps none of them.
   */
  private static MalformedResponseException malformed(JsonParseException cause) {
    return new MalformedResponseException(
        "Context response is malformed: " + cause.getMessage(), cause);
  }

  List<AccessRuleDescription> accessRules() {
    if (cacheOptions.disableCache()) {
      return parseAccessRules(sendCtx("GET", "access/rules", null));
    }
    String key = cacheKey("GET", "access/rules", null);
    String response =
        responseCache.get(
            key,
            ttlNanos("access/rules"),
            () -> {
              String raw = sendCtx("GET", "access/rules", null, key);
              parseAccessRules(raw);
              return raw;
            });
    return parseAccessRules(response);
  }

  private static List<AccessRuleDescription> parseAccessRules(String raw) {
    JsonObject response = Json.parseObject(raw);
    if (!response.has("rules") || !response.get("rules").isJsonArray()) {
      throw new MalformedResponseException(
          "Context access/rules response is missing required rules array");
    }
    try {
      return List.copyOf(WireJson.listFromTree(response.get("rules"), AccessRuleDescription.class));
    } catch (JsonParseException e) {
      throw malformed(e);
    }
  }

  JsonObject getJson(String opWithQuery) {
    return Json.parseObject(sendCtx("GET", opWithQuery, null));
  }

  JsonArray getArray(String opWithQuery) {
    return Json.parseArray(sendCtx("GET", opWithQuery, null));
  }

  /**
   * Read a bare scheme resource that answers a JSON object, with no result envelope. These reads
   * reach the server on every call: the cross-SDK cache contract files only operations, and a
   * resource read is not one.
   */
  <T> T getResource(String opWithQuery, Class<T> type) {
    JsonObject body = getJson(opWithQuery);
    try {
      return WireJson.fromTree(body, type);
    } catch (JsonParseException e) {
      throw malformed(e);
    }
  }

  /**
   * Send an uncached operation whose answer is one contract record, decoded whole. A body the
   * record cannot hold is a malformed response, like every other decode failure.
   */
  <T> T postTyped(String op, JsonObject body, Class<T> type) {
    JsonObject answer = postJson(op, body);
    try {
      return WireJson.fromTree(answer, type);
    } catch (JsonParseException e) {
      throw malformed(e);
    }
  }

  /** Read a bare scheme resource that answers a JSON array, as an immutable typed list. */
  <T> List<T> getResourceList(String opWithQuery, Class<T> elementType) {
    JsonArray body = getArray(opWithQuery);
    try {
      return WireJson.listFromTree(body, elementType);
    } catch (JsonParseException e) {
      throw malformed(e);
    }
  }

  private boolean cacheablePost(String op) {
    return CACHED_POST_OPERATIONS.contains(op)
        || (cacheOptions.cacheAccessDecisions() && "access/check".equals(op));
  }

  private long ttlNanos(String op) {
    if (pinned()) return Long.MAX_VALUE;
    if ("scheme/scan".equals(op)) return Duration.ofMinutes(2).toNanos();
    if ("scheme/describe-concept".equals(op) || "access/rules".equals(op)) {
      return Duration.ofHours(1).toNanos();
    }
    return Duration.ofMinutes(5).toNanos();
  }

  private String cacheKey(String method, String op, JsonElement body) {
    // A version is never empty, so the empty segment of a binding that names none is its own key.
    String binding = version == null ? "" : version;
    return method + "|" + contextId + "|" + binding + "|" + op + "|" + canonicalJson(body);
  }

  private static String canonicalJson(JsonElement value) {
    if (value == null || value.isJsonNull()) return "null";
    if (value.isJsonArray()) {
      StringBuilder result = new StringBuilder("[");
      for (JsonElement child : value.getAsJsonArray()) {
        if (result.length() > 1) result.append(',');
        result.append(canonicalJson(child));
      }
      return result.append(']').toString();
    }
    if (value.isJsonObject()) {
      StringBuilder result = new StringBuilder("{");
      JsonObject object = value.getAsJsonObject();
      for (String name : new TreeSet<>(object.keySet())) {
        if (result.length() > 1) result.append(',');
        result.append(new JsonPrimitive(name)).append(':').append(canonicalJson(object.get(name)));
      }
      return result.append('}').toString();
    }
    return value.toString();
  }

  private static JsonElement requiredResult(JsonObject envelope, EmptyResult empty) {
    JsonElement result = envelope.get("result");
    if (result == null
        || result.isJsonNull()
        || (empty == EmptyResult.REFUSED
            && result.isJsonObject()
            && result.getAsJsonObject().size() == 0)) {
      throw new MalformedResponseException("Context response is missing required result envelope");
    }
    return result;
  }

  private static JsonObject requiredObject(JsonObject envelope, String name) {
    JsonElement value = envelope.get(name);
    if (value == null || value.isJsonNull() || !value.isJsonObject()) {
      throw new MalformedResponseException(
          "Context response is missing required " + name + " envelope");
    }
    return value.getAsJsonObject();
  }

  /**
   * Build the request path for an operation, or for the Context itself. A binding that names no
   * version sends no version segment, so the server chooses. The Context description is the
   * exception: without a version segment that URL lists the Context's versions instead, so the
   * description is asked for at {@code latest}, which the server answers the same way.
   */
  private String opPath(String op) {
    String base = "/v1/ctx/" + segment(contextId);
    String selector = version == null && op.isEmpty() ? "latest" : version;
    if (selector != null) base += "/" + segment(selector);
    return op.isEmpty() ? base : base + "/" + op;
  }

  /**
   * Percent-encode a single path segment. Uses form encoding with {@code +} rewritten to {@code
   * %20}, so unlike {@code encodeURIComponent} the unreserved marks {@code ! ' ( ) * ~} are escaped
   * too. Over-escaping is safe here: the server decodes the segment before matching.
   */
  static String segment(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private String sendCtx(String method, String op, String jsonBody) {
    return sendCtx(method, op, jsonBody, null);
  }

  /**
   * Send one Context request and return its success body. The body is held to the operation's
   * contract shape here, before any reader or the response cache sees it, so a success that lacks a
   * required member fails the call and is never kept.
   */
  private String sendCtx(String method, String op, String jsonBody, String cacheKey) {
    String path = opPath(op);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Accept", "application/json");
    if ("GET".equals(method)) {
      builder.GET();
    } else {
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
    }
    credentials.apply(builder, true);
    HttpResponse<String> response =
        ClientHttp.send(httpClient, builder.build(), path, requestTimeout);
    // A success the contract does not allow is refused before its headers move this handle.
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      ResponseCheck.require(operation(method, op), Json.parse(response.body()));
    }
    // Capture the resolved version before the status check so error
    // responses that still carry the header keep this handle up to date.
    response
        .headers()
        .firstValue(RESOLVED_VERSION_HEADER)
        .filter(v -> !v.isEmpty())
        .ifPresent(
            value -> {
              synchronized (this) {
                if (resolvedVersion != null && !resolvedVersion.equals(value)) {
                  responseCache.clearExcept(cacheKey);
                }
                resolvedVersion = value;
              }
            });
    // A held answer must not outlive the server refusing this caller. The denial 404 is the
    // runtime's refusal of a caller it will not name a route to; a 404 that names a missing
    // resource is an answer about that resource and leaves the cache alone.
    if (response.statusCode() == 401 || ClientHttp.isDenial(response)) responseCache.clear();
    ClientHttp.requireSuccess(response);
    return response.body();
  }

  /**
   * The contract operation a request is, as {@code "METHOD /path/template"}. A binding that names
   * no version leaves the version segment out of the URL, but it is the same operation.
   */
  private static String operation(String method, String op) {
    int query = op.indexOf('?');
    String route = query < 0 ? op : op.substring(0, query);
    return method + " /v1/ctx/{ctxId}/{version}" + (route.isEmpty() ? "" : "/" + route);
  }
}
