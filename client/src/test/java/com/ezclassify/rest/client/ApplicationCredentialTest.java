package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Opaque application transport, refusal and redirect contracts over actual loopback HTTP. */
class ApplicationCredentialTest {
  private static final String GRANT = "opaque.synthetic.application-grant";
  private static final String HEADER = "X-EzClassify-Application-Grant";
  private static final String CONTEXT = "/v1/ctx/sample/1.0.0";

  private static RestClientCredentials application() {
    return new RestClientCredentials(null, null, GRANT);
  }

  private static String header(ClientStubServer stub, String name) {
    return stub.lastRequest().headers().entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(entry -> String.join(",", entry.getValue()))
        .findFirst()
        .orElse(null);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        " leading",
        "trailing ",
        "tab\tvalue",
        "line\r\nvalue",
        "zero\0value",
        "delete\u007fvalue",
        "non-ascii\u00e9"
      })
  void malformedCredentialsRefuseWithoutEcho(String value) {
    var error =
        assertThrows(
            IllegalArgumentException.class, () -> new RestClientCredentials(null, null, value));
    if (!value.isBlank()) assertFalse(error.getMessage().contains(value));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void applicationAndContextCredentialsRefuse(boolean caller) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RestClientCredentials(caller ? "caller" : null, "context", GRANT));
  }

  @Test
  void callerBearerAndApplicationGrantRemainIndependentAcrossNativeClients() throws Exception {
    var path = CONTEXT + "/scheme/normalize";
    var body =
        "{\"resolution\":{\"contextId\":\"sample\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"result\":{\"result\":\"TEXT\"},\"operationLimitations\":[]}";
    try (var stub =
            new ClientStubServer(
                Map.of(
                    CONTEXT,
                    ClientStubServer.description(CONTEXT),
                    path,
                    body,
                    "/v1/health",
                    ClientStubServer.health("1.0.0", "abc")),
                "1.0.0");
        var first =
            new EzClassifyClient(stub.url(), new RestClientCredentials("first-user", null, GRANT));
        var second =
            new EzClassifyClient(
                stub.url(), new RestClientCredentials("second-user", null, GRANT))) {
      var firstContext = first.context("sample", "1.0.0");
      assertEquals("Bearer first-user", header(stub, "Authorization"));
      firstContext.scheme().normalize("text");
      assertEquals("Bearer first-user", header(stub, "Authorization"));
      var secondContext = second.context("sample", "1.0.0");
      assertEquals("Bearer second-user", header(stub, "Authorization"));
      secondContext.scheme().normalize("text");
      assertEquals("Bearer second-user", header(stub, "Authorization"));
      assertEquals(GRANT, header(stub, HEADER));
      assertNull(header(stub, "X-EzClassify-Context-Token"));
      firstContext.scheme().normalize("text");
      assertEquals(4, stub.requestCount());
      first.ping();
      assertEquals("Bearer first-user", header(stub, "Authorization"));
      assertNull(header(stub, HEADER));
      assertNull(header(stub, "X-EzClassify-Context-Token"));
    }
  }

  @Test
  void diagnosticsNeverExposeApplicationMaterial() {
    assertFalse(application().toString().contains(GRANT));
    assertTrue(application().toString().contains("applicationCredential=set"));
    assertEquals(GRANT, application().applicationCredential());
  }

  @Test
  void applicationCredentialUsesExistingPlaintextGuard() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EzClassifyClient("http://remote.example", application()));
    assertThrows(
        IllegalArgumentException.class,
        () -> EzClassifyClient.connect("http://remote.example", "sample", "1.0.0", application()));
  }

  @Test
  void opaqueCredentialReachesContextRoutesAndOperationsButNotHealth() throws Exception {
    try (var stub =
            new ClientStubServer(
                Map.of(
                    "/v1/health",
                    ClientStubServer.health("1.0.0", "abc"),
                    "/v1/ctx",
                    "{\"contexts\":[\"sample\"]}",
                    "/v1/ctx/sample",
                    "{\"id\":\"sample\",\"versions\":[\"1.0.0\"]}",
                    CONTEXT,
                    ClientStubServer.description(CONTEXT),
                    CONTEXT + "/scheme/normalize",
                    "{\"resolution\":{\"contextId\":\"sample\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"result\":{\"result\":\"TEXT\"},\"operationLimitations\":[]}"),
                "1.0.0");
        var root = new EzClassifyClient(stub.url(), application())) {
      root.ping();
      assertNull(header(stub, HEADER));
      assertEquals(List.of("sample"), root.listContexts());
      assertCredential(stub);
      assertEquals(List.of("1.0.0"), root.listContextVersions("sample"));
      assertCredential(stub);
      var bound = root.context("sample", "1.0.0");
      assertCredential(stub);
      assertEquals("TEXT", bound.scheme().normalize("text").result().result());
      assertEquals("POST", stub.lastRequest().method());
      assertCredential(stub);
      var connected = EzClassifyClient.connect(stub.url(), "sample", "1.0.0", application());
      assertEquals("sample", connected.contextId());
      assertCredential(stub);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {".", ".."})
  void dotSegmentsRefuseBeforeTransport(String segment) throws Exception {
    try (var stub = new ClientStubServer(Map.of());
        var root = new EzClassifyClient(stub.url(), application())) {
      assertThrows(IllegalArgumentException.class, () -> root.listContextVersions(segment));
      assertThrows(IllegalArgumentException.class, () -> root.context(segment, "1.0.0"));
      assertThrows(IllegalArgumentException.class, () -> root.context("sample", segment));
      assertThrows(
          IllegalArgumentException.class,
          () -> EzClassifyClient.connect(stub.url(), segment, "1.0.0", application()));
      assertThrows(
          IllegalArgumentException.class,
          () -> EzClassifyClient.connect(stub.url(), "sample", segment, application()));
      assertEquals(0, stub.requestCount());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void forbiddenPostIsNeverRefreshedOrReplayed(boolean factory) throws Exception {
    try (var stub =
            new ClientStubServer(
                Map.of(
                    CONTEXT,
                    ClientStubServer.description(CONTEXT),
                    CONTEXT + "/scheme/normalize",
                    "{\"code\":\"ACCESS_DENIED\",\"detail\":\"refused\"}"),
                "1.0.0");
        var root = new EzClassifyClient(stub.url(), application())) {
      stub.setStatus(CONTEXT + "/scheme/normalize", 403);
      var bound =
          factory
              ? EzClassifyClient.connect(stub.url(), "sample", "1.0.0", application())
              : root.context("sample", "1.0.0");
      var error =
          assertThrows(EzClassifyClientException.class, () -> bound.scheme().normalize("text"));
      assertEquals(403, error.statusCode());
      assertEquals(2, stub.requestCount());
      assertEquals("POST", stub.lastRequest().method());
      assertCredential(stub);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "true,true,false",
    "true,false,false",
    "false,true,false",
    "false,false,false",
    "true,true,true",
    "true,false,true",
    "false,true,true",
    "false,false,true"
  })
  void sameAndCrossOriginRedirectsAreNotFollowed(boolean factory, boolean sameOrigin, boolean user)
      throws Exception {
    var credentials = new RestClientCredentials(user ? "caller" : null, null, GRANT);
    try (var target = new ClientStubServer(Map.of(CONTEXT, ClientStubServer.description(CONTEXT)));
        var source = new ClientStubServer(Map.of(CONTEXT, "{}", "/v1/ctx", "{}"));
        var root = new EzClassifyClient(source.url(), credentials)) {
      var path = factory ? CONTEXT : "/v1/ctx";
      source.setStatus(path, 302);
      source.setHeader(path, "Location", (sameOrigin ? source.url() : target.url()) + CONTEXT);
      var failure =
          assertThrows(
              EzClassifyClientException.class,
              () -> {
                if (factory) EzClassifyClient.connect(source.url(), "sample", "1.0.0", credentials);
                else root.listContexts();
              });
      assertEquals(302, failure.statusCode());
      assertEquals(1, source.requestCount());
      assertEquals(0, target.requestCount());
      assertEquals(GRANT, header(source, HEADER));
      assertEquals(user ? "Bearer caller" : null, header(source, "Authorization"));
      assertNull(header(source, "X-EzClassify-Context-Token"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void redirectWithValidOperationBodyIsRefusedAndNeverCached(boolean applicationMode)
      throws Exception {
    var path = CONTEXT + "/scheme/normalize";
    var body =
        "{\"resolution\":{\"contextId\":\"sample\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"result\":{\"result\":\"TEXT\"},\"operationLimitations\":[]}";
    try (var stub =
            new ClientStubServer(
                Map.of(CONTEXT, ClientStubServer.description(CONTEXT), path, body), "1.0.0");
        var root =
            new EzClassifyClient(
                stub.url(), applicationMode ? application() : RestClientCredentials.none())) {
      var bound = root.context("sample", "1.0.0");
      stub.setStatus(path, 302);
      stub.setHeader(path, "Location", stub.url() + path);
      var failure =
          assertThrows(EzClassifyClientException.class, () -> bound.scheme().normalize("text"));
      assertEquals(302, failure.statusCode());
      assertEquals(2, stub.requestCount());
      stub.setStatus(path, 200);
      stub.setResponse(path, body.replace("TEXT", "FRESH"));
      assertEquals("FRESH", bound.scheme().normalize("text").result().result());
      assertEquals(3, stub.requestCount());
    }
  }

  private static void assertCredential(ClientStubServer stub) {
    assertEquals(GRANT, header(stub, HEADER));
    assertNull(header(stub, "Authorization"));
    assertNull(header(stub, "X-EzClassify-Context-Token"));
  }
}
