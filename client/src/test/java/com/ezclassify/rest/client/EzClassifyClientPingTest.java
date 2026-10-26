package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Version-skew reporting and transport sharing on the server-level client. */
class EzClassifyClientPingTest {

  private static final String MATCHING_HEALTH =
      ClientStubServer.health(SpecVersion.CLIENT_SPEC_VERSION, "abc");
  private static final String SKEWED_HEALTH = ClientStubServer.health("9999.7.0", "def");

  private ClientStubServer stub;
  private Logger logger;
  private Handler capture;
  private Level priorLevel;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    stub =
        new ClientStubServer(
            Map.of("/v1/health", MATCHING_HEALTH, "/v1/ctx", "{\"contexts\":[\"demo\"]}"));
    logger = Logger.getLogger(EzClassifyClient.class.getName());
    capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    capture.setLevel(Level.ALL);
    logger.addHandler(capture);
    priorLevel = logger.getLevel();
    logger.setLevel(Level.ALL);
  }

  @AfterEach
  void tearDown() {
    logger.removeHandler(capture);
    logger.setLevel(priorLevel);
    stub.close();
  }

  @Test
  void aMatchingPingDoesNotSuppressALaterSkewWarning() {
    try (EzClassifyClient client = new EzClassifyClient(stub.url())) {
      client.ping();
      assertEquals(List.of(), messages(), "a matching server version must not warn");

      stub.setResponse("/v1/health", SKEWED_HEALTH);
      client.ping();

      assertEquals(1, messages().size(), "the first genuine skew must be reported");
      String warning = messages().get(0);
      assertTrue(warning.contains(SpecVersion.CLIENT_SPEC_VERSION), warning);
      assertTrue(warning.contains("9999.7.0"), warning);
    }
  }

  @Test
  void theSkewWarningIsReportedOnlyOnce() {
    try (EzClassifyClient client = new EzClassifyClient(stub.url())) {
      stub.setResponse("/v1/health", SKEWED_HEALTH);
      client.ping();
      client.ping();
      assertEquals(1, messages().size(), "the warning is one-shot per client");
    }
  }

  @Test
  void contextHandlesShareTheServerClientTransport() {
    ClientStubServer ctxStub = null;
    try {
      ctxStub =
          new ClientStubServer(
              Map.of("/v1/ctx/demo/1.0.0", ClientStubServer.description("/v1/ctx/demo/1.0.0")),
              "1.0.0");
      try (EzClassifyClient client = new EzClassifyClient(ctxStub.url())) {
        EzClassifyContext first = client.context("demo", "1.0.0");
        EzClassifyContext second = client.context("demo", "1.0.0");
        assertSame(client.transport(), first.transport());
        assertSame(client.transport(), second.transport());
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    } finally {
      if (ctxStub != null) ctxStub.close();
    }
  }

  private List<String> messages() {
    return records.stream().map(LogRecord::getMessage).toList();
  }
}
