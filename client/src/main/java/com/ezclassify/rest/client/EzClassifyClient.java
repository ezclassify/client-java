package com.ezclassify.rest.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Server-level client for the remote EzClassify REST API.
 *
 * <p>The 1.0 REST contract exposes classification operations only through a published {@link
 * EzClassifyContext}. This client owns server discovery, health, and Context binding; it never
 * accepts a bare scheme id or sends a scheme-addressed request.
 *
 * <pre>{@code
 * try (EzClassifyClient client = new EzClassifyClient("http://localhost:8080")) {
 *   System.out.println(client.listContexts());
 *   EzClassifyContext context = client.context("my-context", "1.0.0");
 *   System.out.println(context.scheme().process("LEVEL-A").result().rendered());
 * }
 * }</pre>
 */
public final class EzClassifyClient implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(EzClassifyClient.class.getName());

  /**
   * Ceiling on a single server-level call, response body included; these endpoints are discovery
   * and health only.
   */
  private static final Duration SERVER_REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final String baseUrl;
  private final HttpClient httpClient;
  private final RestClientCredentials credentials;
  private final Duration requestTimeout;
  private final AtomicBoolean versionWarnEmitted = new AtomicBoolean(false);

  /**
   * Create a server-level client for the REST API base URL.
   *
   * @param baseUrl REST API base URL
   * @throws IllegalArgumentException when {@code baseUrl} is not an absolute http or https URL
   */
  public EzClassifyClient(String baseUrl) {
    this(baseUrl, RestClientCredentials.none());
  }

  /**
   * Create a server-level client with independent caller and Context credentials.
   *
   * @param baseUrl REST API base URL
   * @param credentials caller and Context credentials, or {@code null} for none
   * @throws IllegalArgumentException when {@code baseUrl} is not an absolute http or https URL, or
   *     when credentials are supplied for a plaintext http endpoint that is not on loopback
   */
  public EzClassifyClient(String baseUrl, RestClientCredentials credentials) {
    this(baseUrl, credentials, SERVER_REQUEST_TIMEOUT);
  }

  /** As above, with the server-level request deadline. Tests use it to reach one quickly. */
  EzClassifyClient(String baseUrl, RestClientCredentials credentials, Duration requestTimeout) {
    this.requestTimeout = ClientHttp.requireTimeout(requestTimeout);
    this.credentials = credentials == null ? RestClientCredentials.none() : credentials;
    this.baseUrl = Endpoints.requireTransport(baseUrl, this.credentials.present());
    this.httpClient = EzClassifyContext.newHttpClient();
  }

  /**
   * Connect to a named Context at the version the server chooses: the Context token's default
   * version when one is configured, otherwise the newest published version. Like {@code latest},
   * the binding is unpinned.
   *
   * @param baseUrl REST API base URL
   * @param contextId Context identifier
   * @return the resolved Context client
   */
  public static EzClassifyContext connect(String baseUrl, String contextId) {
    return connect(baseUrl, contextId, null);
  }

  /**
   * Connect to a named, versioned Context and validate the binding before returning it.
   *
   * @param baseUrl REST API base URL
   * @param contextId Context identifier
   * @param version concrete Context version, {@code latest}, or {@code null} to let the server
   *     choose
   * @return the resolved Context client
   */
  public static EzClassifyContext connect(String baseUrl, String contextId, String version) {
    return connect(baseUrl, contextId, version, RestClientCredentials.none());
  }

  /**
   * Connect with independent caller-identity and Context-authorization credentials.
   *
   * @param baseUrl REST API base URL
   * @param contextId Context identifier
   * @param version concrete Context version, {@code latest}, or {@code null} to let the server
   *     choose
   * @param credentials caller and Context credentials, or {@code null} for none
   * @return the resolved Context client
   */
  public static EzClassifyContext connect(
      String baseUrl, String contextId, String version, RestClientCredentials credentials) {
    return connect(baseUrl, contextId, version, credentials, ContextCacheOptions.defaults());
  }

  /**
   * Connect with explicit response-cache choices for the bound Context.
   *
   * @param baseUrl REST API base URL
   * @param contextId Context identifier
   * @param version immutable Context version, {@code latest}, or {@code null} to let the server
   *     choose
   * @param credentials caller and Context credentials, or {@code null} for none
   * @param cacheOptions response reuse and access-audit choices
   * @return the resolved Context client
   * @throws IllegalArgumentException if access-decision caching is requested without a concrete
   *     Context version
   */
  public static EzClassifyContext connect(
      String baseUrl,
      String contextId,
      String version,
      RestClientCredentials credentials,
      ContextCacheOptions cacheOptions) {
    EzClassifyContext context =
        new EzClassifyContext(
            baseUrl,
            contextId,
            version,
            credentials,
            EzClassifyContext.newHttpClient(),
            cacheOptions);
    context.resolve();
    return context;
  }

  /**
   * Bind this server client to a named Context at the version the server chooses: this client's
   * Context token default, otherwise the newest published version.
   *
   * @param contextId Context identifier
   * @return the resolved Context client
   */
  public EzClassifyContext context(String contextId) {
    return context(contextId, null);
  }

  /**
   * Bind this server client to a named, versioned Context.
   *
   * @param contextId Context identifier
   * @param version concrete Context version, {@code latest}, or {@code null} to let the server
   *     choose
   * @return the resolved Context client
   */
  public EzClassifyContext context(String contextId, String version) {
    return context(contextId, version, ContextCacheOptions.defaults());
  }

  /**
   * Bind this server client with explicit response-cache choices.
   *
   * @param contextId Context identifier
   * @param version immutable Context version, {@code latest}, or {@code null} to let the server
   *     choose
   * @param cacheOptions response reuse and access-audit choices
   * @return the resolved Context client
   * @throws IllegalArgumentException if access-decision caching is requested without a concrete
   *     Context version
   */
  public EzClassifyContext context(
      String contextId, String version, ContextCacheOptions cacheOptions) {
    EzClassifyContext context =
        new EzClassifyContext(baseUrl, contextId, version, credentials, httpClient, cacheOptions);
    context.resolve();
    return context;
  }

  /**
   * List all Context ids visible to this deployment, sorted by the server.
   *
   * @return visible Context identifiers
   */
  public List<String> listContexts() {
    return stringList(requiredArray(getJson("/v1/ctx"), "contexts"));
  }

  /**
   * List the published versions of one Context, highest first.
   *
   * @param contextId Context identifier
   * @return published immutable versions
   */
  public List<String> listContextVersions(String contextId) {
    if (contextId == null || contextId.isBlank()) {
      throw new IllegalArgumentException("contextId must not be empty");
    }
    JsonObject response = getJson("/v1/ctx/" + EzClassifyContext.segment(contextId));
    return stringList(requiredArray(response, "versions"));
  }

  /**
   * Return the engine spec version this client was built against.
   *
   * @return vendored engine specification version
   */
  public String engineSpecVersion() {
    return SpecVersion.CLIENT_SPEC_VERSION;
  }

  /**
   * Ping the live REST API and return its engine version and build identity.
   *
   * @return live runtime version and build identity
   */
  public PingResult ping() {
    JsonObject response = getJson("/v1/health");
    String engineVersion = Json.optString(response, "engineVersion", "");
    String engineSha = Json.optString(response, "engineSha", "");
    String gitTag = Json.optString(response, "gitTag", "");
    if (!majorMinor(SpecVersion.CLIENT_SPEC_VERSION).equals(majorMinor(engineVersion))
        && versionWarnEmitted.compareAndSet(false, true)) {
      LOG.warning(
          () ->
              String.format(
                  "This client is built for rest-api v%s but is connected to rest-api v%s."
                      + " Mixed minor versions are unsupported.",
                  SpecVersion.CLIENT_SPEC_VERSION, engineVersion));
    }
    return new PingResult(engineVersion, engineSha, gitTag);
  }

  @Override
  public void close() {
    // java.net.http.HttpClient has no close operation on the supported Java baseline.
  }

  /** The HTTP client this instance sends through. Package-private: pool sharing is an internal. */
  HttpClient transport() {
    return httpClient;
  }

  private JsonObject getJson(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Accept", "application/json")
            .GET();
    credentials.apply(builder, path.startsWith("/v1/ctx"));
    HttpRequest request = builder.build();
    HttpResponse<String> response = ClientHttp.send(httpClient, request, path, requestTimeout);
    ClientHttp.requireSuccess(response);
    JsonObject body = Json.parseObject(response.body());
    ResponseCheck.require("GET " + template(path), body);
    return body;
  }

  /** The contract route a server-level path is: every path below {@code /v1/ctx/} names one id. */
  private static String template(String path) {
    return path.startsWith("/v1/ctx/") ? "/v1/ctx/{ctxId}" : path;
  }

  private static JsonArray requiredArray(JsonObject object, String field) {
    JsonElement value = object.get(field);
    if (value == null || !value.isJsonArray()) {
      throw new MalformedResponseException("REST response is missing required " + field + " array");
    }
    return value.getAsJsonArray();
  }

  private static List<String> stringList(JsonArray values) {
    List<String> result = new ArrayList<>(values.size());
    for (JsonElement value : values) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new MalformedResponseException("REST response contains a non-string Context value");
      }
      result.add(value.getAsString());
    }
    return List.copyOf(result);
  }

  private static String majorMinor(String version) {
    if (version == null || version.isEmpty()) return "0.0";
    String[] parts = version.split("\\.");
    return (parts.length > 0 ? parts[0] : "0") + "." + (parts.length > 1 ? parts[1] : "0");
  }
}
