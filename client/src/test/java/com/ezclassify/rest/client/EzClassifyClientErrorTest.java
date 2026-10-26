package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Server-level discovery, validation, and transport-error contracts. */
class EzClassifyClientErrorTest {

  @Test
  void discoveryListsContextsAndVersions() throws Exception {
    try (ClientStubServer stub =
            new ClientStubServer(
                Map.of(
                    "/v1/ctx", "{\"contexts\":[\"alpha\",\"bravo\"]}",
                    "/v1/ctx/alpha", "{\"id\":\"alpha\",\"versions\":[\"1.1.0\",\"1.0.0\"]}"));
        EzClassifyClient client = stub.newClient()) {
      assertEquals(java.util.List.of("alpha", "bravo"), client.listContexts());
      assertEquals(java.util.List.of("1.1.0", "1.0.0"), client.listContextVersions("alpha"));
      assertEquals("/v1/ctx/alpha", stub.lastRequest().path());
    }
  }

  @Test
  void discoveryRejectsMalformedBody() throws Exception {
    try (ClientStubServer stub = new ClientStubServer(Map.of("/v1/ctx", "{\"contexts\":[1]}"));
        EzClassifyClient client = stub.newClient()) {
      assertThrows(EzClassifyClientException.class, client::listContexts);
    }
  }

  @Test
  void missingContextPreservesStructuredError() throws Exception {
    try (ClientStubServer stub = new ClientStubServer(Map.of());
        EzClassifyClient client = stub.newClient()) {
      EzClassifyClientException error =
          assertThrows(
              EzClassifyClientException.class, () -> client.listContextVersions("missing"));
      assertEquals(404, error.statusCode());
    }
  }

  @Test
  void constructorAndContextIdValidateBeforeTransport() {
    assertThrows(IllegalArgumentException.class, () -> new EzClassifyClient(null));
    assertThrows(IllegalArgumentException.class, () -> new EzClassifyClient("   "));
    EzClassifyClient client = new EzClassifyClient("http://localhost:1");
    assertThrows(IllegalArgumentException.class, () -> client.listContextVersions(""));
    assertDoesNotThrow(client::close);
    assertDoesNotThrow(client::close);
  }

  @Test
  void baseUrlMustBeAnAbsoluteHttpUrl() {
    assertEquals(
        "baseUrl must be an absolute http or https URL",
        assertThrows(
                IllegalArgumentException.class, () -> new EzClassifyClient("rest-api.example.com"))
            .getMessage());
    assertEquals(
        "baseUrl must include a host",
        assertThrows(IllegalArgumentException.class, () -> new EzClassifyClient("http:///v1"))
            .getMessage());
  }

  @Test
  void credentialsAreRefusedOverPlaintextHttpToARemoteHost() {
    RestClientCredentials credentials = new RestClientCredentials("caller-token", null);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EzClassifyClient("http://rest-api.example.com", credentials));
    assertTrue(
        thrown.getMessage().contains("must use https"),
        "the failure should name the transport rule, was: " + thrown.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> EzClassifyClient.connect("http://rest-api.example.com", "ctx", "1.0.0", credentials));
  }

  @Test
  void credentialsTravelOverHttpsAndOverLoopbackHttp() {
    RestClientCredentials credentials = new RestClientCredentials("caller-token", "ctx-token");
    assertDoesNotThrow(
        () -> new EzClassifyClient("https://rest-api.example.com", credentials).close());
    assertDoesNotThrow(() -> new EzClassifyClient("http://localhost:1", credentials).close());
    assertDoesNotThrow(() -> new EzClassifyClient("http://127.0.0.1:1/", credentials).close());
  }

  @Test
  void aRegisteredNameThatMerelyStartsWithTheLoopbackPrefixIsRemote() {
    RestClientCredentials credentials = new RestClientCredentials("caller-token", "ctx-token");
    for (String remote :
        new String[] {"http://127.attacker.example", "http://127.0.0.1.attacker.example"}) {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> new EzClassifyClient(remote, credentials),
              remote + " is not a loopback literal and must not take credentials over http");
      assertTrue(
          thrown.getMessage().contains("must use https"),
          "the failure should name the transport rule, was: " + thrown.getMessage());
    }
  }

  @Test
  void plaintextHttpIsStillFineWithoutCredentials() {
    assertDoesNotThrow(() -> new EzClassifyClient("http://rest-api.example.com").close());
  }
}
