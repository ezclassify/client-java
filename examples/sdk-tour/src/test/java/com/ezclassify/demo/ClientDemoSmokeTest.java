package com.ezclassify.demo;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The runnable {@link ClientDemo} is the quickstart for the HTTP client, so it has to keep working
 * end to end. A JDK built-in {@code com.sun.net.httpserver} stub plays the REST API with canned
 * responses in the real wire shapes, the demo runs against it once, and the output must show every
 * numbered section. If a client API change breaks the demo, or a section is dropped, this fails
 * before the example ships broken to a new integrator.
 */
@SuppressWarnings("restriction")
class ClientDemoSmokeTest {

  private static HttpServer stub;
  private static String baseUrl;

  private static final String HEALTH =
      """
            {"status":"ok","engineVersion":"1.0.0","engineSha":"abc123","gitTag":"v1.0.0",
             "openapiVersion":"1.0.0","abiVersion":"v1"}
            """;

  private static final String PROCESS =
      """
            {"rendered":"CONFIDENTIAL","valid":true,"inputWasValid":true,
             "assertions":[{"conceptId":"CONFIDENTIAL","categoryId":"level","confidence":"certain","source":"parse"}],
             "errors":[],"warnings":[],"proposedFixes":[],"appliedFixes":[],"renders":{},
             "assessment":{"emitted":[{"conceptId":"CONFIDENTIAL","categoryId":"level"}],"omitted":[],"collapsed":[],"conflicts":[],"unrepresentable":[]},
             "notices":[{"conceptId":"CONFIDENTIAL","conceptName":"Confidential","type":"handling",
                         "text":"store in an approved container","required":true}]}
            """;

  private static final String VALIDATE =
      """
            {"valid":false,"inputWasValid":false,"rendered":"","assertions":[],"warnings":[],"appliedFixes":[],
             "assessment":{"emitted":[],"omitted":[],"collapsed":[],"conflicts":[],"unrepresentable":[]},
             "errors":[{"constraintId":"_recognition_incomplete","message":"Could not read UNRECOGNIZED"}],
             "notices":[]}
            """;

  private static final String AGGREGATE =
      """
            {"rendered":"CONFIDENTIAL//FINANCIAL INFORMATION","valid":true,"assertions":[],"notices":[],
             "errors":[],"warnings":[],"appliedFixes":[],"proposedFixes":[]}
            """;

  private static final String SUGGEST =
      """
            {"suggestions":[{"conceptId":"PUBLIC","categoryId":"level","label":"PUBLIC",
             "description":"Public","name":"Public","views":{"default":"PUBLIC"},"group":"Protection",
             "selected":false,"order":0}],"warnings":[]}
            """;

  private static final String GUIDE_SCAN =
      """
            [{"guideId":"cedar-valley-guide","matchedKeywords":["supplier bid"],
              "rule":{"id":"confidential","name":"Confidential","keywords":["supplier bid"],"category":"protection",
                      "outcome":{"rendered":"CONFIDENTIAL"}},
              "spans":[{"keyword":"supplier bid","start":9,"end":21}]}]
            """;

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    String ctx = "/v1/ctx/cedar-valley/1.0.0";
    Map.of(
            "/v1/health",
            HEALTH,
            "/v1/ctx",
            "{\"contexts\":[\"cedar-valley\"]}",
            ctx,
            "{\"id\":\"cedar-valley\",\"version\":\"1.0.0\",\"scheme\":{\"id\":\"cedar-valley-scheme\","
                + "\"version\":\"1.0.0\",\"resolvedVersion\":\"1.0.0\"},\"schemes\":[]}",
            ctx + "/scheme/process",
            envelope(PROCESS),
            ctx + "/scheme/validate",
            envelope(VALIDATE),
            ctx + "/scheme/aggregate",
            envelope(AGGREGATE),
            ctx + "/scheme/suggest",
            envelope(SUGGEST),
            ctx + "/guide/scan",
            envelope(GUIDE_SCAN))
        .forEach((path, json) -> stub.createContext(path, new JsonHandler(json)));
    stub.start();
    baseUrl = "http://localhost:" + stub.getAddress().getPort();
  }

  private static String envelope(String result) {
    return "{\"resolution\":{\"contextId\":\"cedar-valley\",\"requestedVersion\":\"1.0.0\",\"resolvedVersion\":\"1.0.0\",\"artifacts\":[],\"sources\":[]},\"operationLimitations\":[],\"result\":"
        + result
        + "}";
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @Test
  void clientDemoRunsEverySectionToCompletion() {
    PrintStream original = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf));
    try {
      new ClientDemo(baseUrl, "cedar-valley", "1.0.0").run();
    } finally {
      System.setOut(original);
    }

    String out = buf.toString();
    assertTrue(out.contains("EzClassify Java Client Demo"), "demo banner missing");
    assertTrue(out.contains("1. Ping"), "ping section missing");
    assertTrue(out.contains("2. Validate markings"), "validate section missing");
    assertTrue(out.contains("3. Process a marking"), "process section missing");
    assertTrue(out.contains("4. Aggregate"), "aggregate section missing");
    assertTrue(out.contains("5. Suggest"), "suggest section missing");
    assertTrue(out.contains("6. Guide scan"), "guide scan section missing");
    // The process line reports the notices the result carried, so the demo shows that a rich
    // result answers the notices question without a second call.
    assertTrue(out.contains("notices=1"), "process notices count missing");
    assertTrue(out.contains("notice [handling]: store in an approved container"), "notice missing");
    assertTrue(out.contains("- PUBLIC (PUBLIC)"), "picker option missing");
    assertTrue(out.contains("Confidential matched supplier bid"), "guide match missing");
    assertTrue(out.contains("Client demo complete."), "completion line missing");
  }

  @Test
  void constructorRejectsBlankArguments() {
    assertThrows(IllegalArgumentException.class, () -> new ClientDemo(" ", "scheme", "1.0.0"));
    assertThrows(IllegalArgumentException.class, () -> new ClientDemo(baseUrl, "", "1.0.0"));
    assertThrows(IllegalArgumentException.class, () -> new ClientDemo(baseUrl, "scheme", " "));
  }

  /** Replies 200 with a fixed JSON body to every request. */
  private record JsonHandler(String json) implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    }
  }
}
