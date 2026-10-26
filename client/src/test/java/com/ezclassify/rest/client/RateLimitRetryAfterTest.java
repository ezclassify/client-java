package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 429 refusal the contract declares on every operation. The server answers {@code
 * RateLimitError} with a {@code Retry-After} header; the client surfaces the wait on {@link
 * EzClassifyClientException#retryAfter()} and never waits or retries itself.
 */
class RateLimitRetryAfterTest {

  private static final OpenApiSchemas SPEC = OpenApiSchemas.load();

  private static final String CTX = "/v1/ctx/acme-marking/1.0.0";

  private static String rateLimitBody(String retryAfterSeconds) {
    String body = "{\"error\":\"Too Many Requests\",\"retryAfter\":" + retryAfterSeconds + "}";
    // The stub answers exactly what the contract's RateLimitError describes.
    assertEquals(List.of(), SPEC.validateComponent("RateLimitError", JsonParser.parseString(body)));
    return body;
  }

  /** A call and the route it makes, covering the server-level and Context-bound transports. */
  private static Stream<Arguments> calls() {
    return Stream.of(
        call("health", "/v1/health", url -> new EzClassifyClient(url).ping()),
        call("ctx-list", "/v1/ctx", url -> new EzClassifyClient(url).listContexts()),
        call(
            "ctx-describe",
            CTX,
            url -> new EzClassifyContext(url, "acme-marking", "1.0.0", null).resolve()),
        call(
            "describe-concept",
            CTX + "/scheme/describe-concept",
            url ->
                new EzClassifyContext(url, "acme-marking", "1.0.0", null)
                    .scheme()
                    .describeConcept("HIGH", "level")),
        call("scheme-summary", CTX + "/scheme", url -> scheme(url).summary()),
        call("scheme-concepts", CTX + "/scheme/concepts", url -> scheme(url).concepts()),
        call("scheme-categories", CTX + "/scheme/categories", url -> scheme(url).categories()),
        call("scheme-formats", CTX + "/scheme/formats", url -> scheme(url).formats()),
        call("scheme-grammar", CTX + "/scheme/grammar", url -> scheme(url).grammar()),
        call("scheme-scopes", CTX + "/scheme/scopes", url -> scheme(url).scopes()),
        call(
            "scheme-label-presets",
            CTX + "/scheme/label-presets",
            url -> scheme(url).labelPresets()),
        call(
            "process",
            CTX + "/scheme/process",
            url ->
                new EzClassifyContext(url, "acme-marking", "1.0.0", null).scheme().process("H")));
  }

  private static ContextScheme scheme(String url) {
    return new EzClassifyContext(url, "acme-marking", "1.0.0", null).scheme();
  }

  private static Arguments call(String name, String route, Consumer<String> invoke) {
    return Arguments.of(name, route, invoke);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calls")
  void aRateLimitRefusalCarriesTheHeaderWaitOnEveryCall(
      String name, String route, Consumer<String> invoke) throws Exception {
    try (ClientStubServer stub = new ClientStubServer(Map.of(route, rateLimitBody("30")))) {
      stub.setStatus(route, 429);
      stub.setHeader(route, "Retry-After", "7");

      EzClassifyClientException error =
          assertThrows(EzClassifyClientException.class, () -> invoke.accept(stub.url()));

      assertEquals(429, error.statusCode(), name);
      assertEquals("Too Many Requests", error.getMessage(), name);
      // The header wins over the body's copy of the same wait.
      assertEquals(Duration.ofSeconds(7), error.retryAfter(), name);
      assertEquals(1, stub.requestCount(), "the client must not retry a rate-limited call");
    }
  }

  @Test
  void theBodyWaitAnswersWhenTheHeaderIsAbsent() throws Exception {
    for (var entry :
        Map.of("12", Duration.ofSeconds(12), "1.5", Duration.ofMillis(1500)).entrySet()) {
      try (ClientStubServer stub =
          new ClientStubServer(Map.of("/v1/ctx", rateLimitBody(entry.getKey())))) {
        stub.setStatus("/v1/ctx", 429);
        EzClassifyClientException error =
            assertThrows(EzClassifyClientException.class, () -> stub.newClient().listContexts());
        assertEquals(entry.getValue(), error.retryAfter(), entry.getKey());
      }
    }
  }

  @Test
  void aHeaderThatIsNotSecondsFallsBackToTheBody() throws Exception {
    for (String header : new String[] {"Wed, 21 Oct 2026 07:28:00 GMT", "-5", "soon", ""}) {
      try (ClientStubServer stub = new ClientStubServer(Map.of("/v1/ctx", rateLimitBody("9")))) {
        stub.setStatus("/v1/ctx", 429);
        stub.setHeader("/v1/ctx", "Retry-After", header);
        EzClassifyClientException error =
            assertThrows(EzClassifyClientException.class, () -> stub.newClient().listContexts());
        assertEquals(Duration.ofSeconds(9), error.retryAfter(), "header " + header);
      }
    }
  }

  @Test
  void aRateLimitRefusalWithNoUsableWaitHasNone() throws Exception {
    for (String body :
        new String[] {"{\"error\":\"Too Many Requests\"}", "{\"retryAfter\":-3}", "not json"}) {
      try (ClientStubServer stub = new ClientStubServer(Map.of("/v1/ctx", body))) {
        stub.setStatus("/v1/ctx", 429);
        EzClassifyClientException error =
            assertThrows(EzClassifyClientException.class, () -> stub.newClient().listContexts());
        assertEquals(429, error.statusCode(), body);
        assertNull(error.retryAfter(), body);
      }
    }
  }

  @Test
  void onlyARateLimitRefusalCarriesAWait() throws Exception {
    for (int status : new int[] {400, 404, 500, 503}) {
      try (ClientStubServer stub =
          new ClientStubServer(Map.of("/v1/ctx", "{\"error\":\"no\",\"retryAfter\":5}"))) {
        stub.setStatus("/v1/ctx", status);
        stub.setHeader("/v1/ctx", "Retry-After", "5");
        EzClassifyClientException error =
            assertThrows(EzClassifyClientException.class, () -> stub.newClient().listContexts());
        assertEquals(status, error.statusCode());
        assertNull(error.retryAfter(), "status " + status);
      }
    }
  }
}
