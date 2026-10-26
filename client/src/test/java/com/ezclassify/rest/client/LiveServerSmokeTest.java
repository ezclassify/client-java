package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.RecognitionStatus;
import com.ezclassify.rest.contract.RenderPreservation;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live acceptance against a caller-provided licensed and content-configured REST deployment. */
@Tag("live")
class LiveServerSmokeTest {
  private static final String CONTEXT_ID = "cedar-valley-protection";

  @Test
  void processAndHealthAgainstLiveV1Server() throws Exception {
    String preset = firstNonBlank(env("EZC_SMOKE_URL"), System.getProperty("ezc.smoke.url"));
    assertNotNull(preset, "make smoke requires URL=<licensed-rest-deployment>");
    assertTrue(!preset.isBlank(), "live deployment URL must not be blank");
    String baseUrl = preset.replaceAll("/+$", "");
    // 1. /v1/health through the SDK's own ping(): proves the GET path and
    //    the engine-version contract.
    RestClientCredentials credentials =
        new RestClientCredentials(env("EZC_SMOKE_ACCESS_TOKEN"), env("EZC_SMOKE_CONTEXT_TOKEN"));
    EzClassifyClient client = new EzClassifyClient(baseUrl, credentials);
    PingResult ping = client.ping();
    assertNotNull(ping.engineVersion(), "ping returned null engineVersion");
    assertTrue(
        ping.engineVersion().startsWith("1."),
        "expected a 1.x server, got " + ping.engineVersion());

    // 2. Context discovery and process prove the public Context-only routes
    //    and request/response shape end to end.
    assertTrue(
        client.listContexts().contains(smokeContextId()),
        "live server must expose the smoke Context");
    EzClassifyContext context = client.context(smokeContextId(), "latest");
    List<String> outputs = smokeOutputs();
    ProcessResult result =
        context
            .scheme()
            .process(smokeInput(), ProcessOptions.builder().outputs(outputs).build())
            .result();
    assertTrue(result.valid(), "process result should be valid; got " + result);
    assertEquals(
        smokeExpected(), result.rendered(), "input should normalize to the expected rendering");
    assertEquals(RecognitionStatus.COMPLETE, result.recognition().status());
    assertEquals(
        outputs,
        List.copyOf(result.renders().keySet()),
        "live server must return exactly the outputs the request named");
    assertTrue(
        result.renders().values().stream().allMatch(render -> render.assessment() != null),
        "every named render must carry its preservation assessment");
    // 3. The scheme resource reads answer the bare views the contract declares.
    ContextScheme scheme = context.scheme();
    assertTrue(!scheme.summary().id().isBlank(), "summary must name the scheme");
    assertTrue(!scheme.categories().isEmpty(), "a scheme declares categories");
    assertTrue(!scheme.formats().isEmpty(), "a scheme writes at least one format");
    assertNotNull(scheme.grammar());
    assertNotNull(scheme.scopes());
    assertNotNull(scheme.labelPresets());
    EzClassifyClientException refusal =
        assertThrows(
            EzClassifyClientException.class,
            () ->
                context
                    .scheme()
                    .render(
                        result.assertions(),
                        RenderOptions.builder()
                            .outputFormat("authority_block")
                            .preservation(RenderPreservation.FULL)
                            .build()));
    assertEquals(400, refusal.statusCode());
    assertEquals("INVALID_INPUT", refusal.code());
  }

  private static String smokeContextId() {
    return firstNonBlank(env("EZC_SMOKE_CONTEXT_ID"), CONTEXT_ID);
  }

  private static String smokeInput() {
    return firstNonBlank(env("EZC_SMOKE_INPUT"), "confidential");
  }

  private static String smokeExpected() {
    return firstNonBlank(env("EZC_SMOKE_EXPECTED"), "CONFIDENTIAL");
  }

  /** Outputs the smoke Context declares; process writes into {@code renders} only these. */
  private static List<String> smokeOutputs() {
    return List.of(firstNonBlank(env("EZC_SMOKE_OUTPUTS"), "default,short").split(","));
  }

  private static String env(String name) {
    return System.getenv(name);
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }
}
