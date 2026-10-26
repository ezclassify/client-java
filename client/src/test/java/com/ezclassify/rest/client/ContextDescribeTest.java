package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.CtxDescribeResponseBody;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Context description is connection state: read at connect, held, and kept current. */
class ContextDescribeTest {
  private static final String LATEST = "/v1/ctx/records/latest";
  private static final String PINNED = "/v1/ctx/records/1.0.0";

  private static String description(String version, String schemeVersion, String extra) {
    return "{\"id\":\"records\",\"version\":\""
        + version
        + "\",\"scheme\":{\"id\":\"base\",\"version\":\"latest\",\"resolvedVersion\":\""
        + schemeVersion
        + "\",\"layers\":[{\"id\":\"overlay\",\"version\":\"1.0.0\",\"resolvedVersion\":\"1.0.0\"}]},"
        + "\"schemes\":[{\"selector\":\"base\",\"id\":\"base\",\"version\":\""
        + schemeVersion
        + "\",\"roles\":[\"primary\"]}]"
        + extra
        + "}";
  }

  @Test
  void connectHoldsTheDescriptionWithDeclaredAndResolvedVersions() throws Exception {
    String crosswalks =
        ",\"crosswalks\":[{\"id\":\"to-gated\",\"version\":\"latest\",\"resolvedVersion\":\"1.2.0\"}]"
            + ",\"access\":{\"id\":\"policy\",\"version\":\"1.0.0\",\"resolvedVersion\":\"1.0.0\"}"
            + ",\"guides\":[{\"id\":\"g\",\"version\":\"1.0.0\",\"resolvedVersion\":\"1.0.0\"}]";
    try (ClientStubServer stub =
        new ClientStubServer(Map.of(PINNED, description("1.0.0", "1.1.0", crosswalks)), "1.0.0")) {
      EzClassifyContext context = stub.newClient().context("records", "1.0.0");
      assertEquals(1, stub.requestCount());

      CtxDescribeResponseBody described = context.describeContext();
      context.clearCache();
      assertEquals(described, context.describeContext());
      assertEquals(1, stub.requestCount(), "held at connect; clearCache leaves connection state");

      assertEquals("records", described.id());
      assertEquals("1.0.0", described.version());
      assertEquals("latest", described.scheme().version());
      assertEquals("1.1.0", described.scheme().resolvedVersion());
      assertEquals("overlay", described.scheme().layers().get(0).id());
      assertEquals("to-gated", described.crosswalks().get(0).id());
      assertEquals("1.2.0", described.crosswalks().get(0).resolvedVersion());
      assertEquals("policy", described.access().id());
      assertEquals("g", described.guides().get(0).id());
      assertEquals("base", described.schemes().get(0).selector());
    }
  }

  @Test
  void componentsTheContextDoesNotDeclareReadAsEmpty() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(Map.of(PINNED, description("1.0.0", "1.0.0", "")), "1.0.0")) {
      CtxDescribeResponseBody described =
          stub.newClient().context("records", "1.0.0").describeContext();
      assertTrue(described.guides().isEmpty());
      assertTrue(described.crosswalks().isEmpty());
      assertNull(described.access());
    }
  }

  @Test
  void anUnpinnedBindingReadsTheDescriptionAgainOnceTheCompositionMoves() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(Map.of(LATEST, description("1.0.0", "1.0.0", "")), "1.0.0")) {
      EzClassifyContext context = stub.newClient().context("records", "latest");
      assertEquals("1.0.0", context.describeContext().version());
      assertEquals(1, stub.requestCount());

      stub.setResponse(LATEST, description("2.0.0", "2.0.0", ""));
      stub.setResolvedVersionHeader("2.0.0");
      context.resolve();
      assertEquals("2.0.0", context.describeContext().version());
      assertEquals(2, stub.requestCount());
    }
  }

  @Test
  void aDescriptionMissingARequiredMemberFailsTheBinding() throws Exception {
    try (ClientStubServer stub =
        new ClientStubServer(
            Map.of(PINNED, "{\"id\":\"records\",\"version\":\"1.0.0\",\"schemes\":[]}"), "1.0.0")) {
      EzClassifyClient client = stub.newClient();
      MalformedResponseException refused =
          assertThrows(MalformedResponseException.class, () -> client.context("records", "1.0.0"));
      assertEquals("$.scheme", refused.path());
    }
  }
}
