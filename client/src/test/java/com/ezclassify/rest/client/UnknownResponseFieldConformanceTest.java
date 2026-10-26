package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.ContextSchemeProcessResult;
import com.ezclassify.rest.contract.ProcessResult;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Responses are open: every schema a response can reach tolerates fields this SDK does not know.
 *
 * <p>This is the Java side of the cross-SDK conformance case for that rule. A server running ahead
 * of a pinned client adds optional fields; a client that refused them would break on a compatible
 * server upgrade. The rule is one-directional: requests stay closed and the server rejects unknown
 * fields on the way in, so this test only exercises decoding.
 *
 * <p>Gson ignores unknown members by default, which is the Java equivalent of configuring Jackson
 * with {@code FAIL_ON_UNKNOWN_PROPERTIES} off. That default is a behavior this SDK depends on, so
 * it is pinned here rather than assumed.
 */
class UnknownResponseFieldConformanceTest {

  /**
   * A scheme/process envelope with unknown members at four levels: alongside the envelope fields,
   * inside the resolution, inside the result, and inside a nested assertion.
   */
  private static final String ENVELOPE_WITH_UNKNOWN_FIELDS =
      "{\"resolution\":{\"contextId\":\"acme-ctx\",\"requestedVersion\":\"1.0.0\","
          + "\"resolvedVersion\":\"9.9.9\",\"artifacts\":[],\"sources\":[],"
          + "\"resolutionPolicyAddedLater\":\"pinned\"},"
          + "\"result\":{\"valid\":true,\"rendered\":\"H\",\"inputWasValid\":true,"
          + "\"assertions\":[{\"conceptId\":\"L_H\",\"categoryId\":\"level\","
          + "\"confidence\":\"certain\",\"source\":\"parse\","
          + "\"assertionFieldAddedLater\":{\"nested\":[1,2,3]}}],"
          + "\"errors\":[],\"warnings\":[],\"notices\":[],"
          + "\"appliedFixes\":[],\"proposedFixes\":[],\"renders\":{},"
          + "\"assessment\":{\"emitted\":[],\"omitted\":[],\"collapsed\":[],\"conflicts\":[],"
          + "\"unrepresentable\":[]},"
          + "\"resultFieldAddedLater\":\"ignored\"},"
          + "\"operationLimitations\":[],\"envelopeFieldAddedLater\":[\"ignored\"]}";

  private static ClientStubServer stub;

  @BeforeAll
  static void start() throws IOException {
    stub =
        new ClientStubServer(
            Map.of(
                "/v1/ctx/acme-ctx/1.0.0",
                ClientStubServer.description("/v1/ctx/acme-ctx/1.0.0"),
                "/v1/ctx/acme-ctx/1.0.0/scheme/process",
                ENVELOPE_WITH_UNKNOWN_FIELDS),
            "9.9.9");
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  @Test
  void unknownFieldsAnywhereInAResponseAreIgnoredAndTheKnownFieldsSurvive() {
    EzClassifyContext ctx = EzClassifyClient.connect(stub.url(), "acme-ctx", "1.0.0");
    ContextSchemeProcessResult response =
        assertDoesNotThrow(() -> ctx.scheme().process("H"), "unknown response fields must decode");

    assertEquals("9.9.9", response.resolution().resolvedVersion());
    ProcessResult result = response.result();
    assertTrue(result.valid());
    assertEquals("H", result.rendered());
    assertEquals(1, result.assertions().size());
    assertEquals("L_H", result.assertions().get(0).conceptId());
    assertEquals("level", result.assertions().get(0).categoryId());
  }

  @Test
  void unknownFieldsAreIgnoredByTheTypedWireDecoderItself() {
    ProcessResult result =
        WireJson.fromTree(
            Json.parseObject(
                "{\"valid\":false,\"rendered\":\"X\",\"inputWasValid\":false,\"assertions\":[],"
                    + "\"errors\":[],\"warnings\":[],\"futureField\":{\"a\":1}}"),
            ProcessResult.class);
    assertEquals("X", result.rendered());
    assertTrue(result.errors().isEmpty());
  }
}
