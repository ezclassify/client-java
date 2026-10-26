package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every public call finishes within its request deadline, however the server stalls: before the
 * headers, or after them partway through the body. The deadline covers the whole exchange, ends it
 * when it passes, and is reported apart from a caller interrupting the call.
 */
@SuppressWarnings("restriction")
class RequestDeadlineTest {

  private static final Duration DEADLINE = Duration.ofMillis(400);

  /** Longer than any test waits, so only the client's deadline can end a stalled exchange. */
  private static final long STALL_SECONDS = 20;

  private HttpServer server;
  private final ExecutorService handlers = Executors.newCachedThreadPool();
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch tornDown = new CountDownLatch(1);

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext("/v1/health", this::stallAfterHeaders);
    server.createContext("/v1/ctx/acme/1.0.0/scheme/process", this::stallAfterHeaders);
    server.createContext("/v1/ctx/acme/1.0.0", this::stallBeforeHeaders);
    server.start();
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
    handlers.shutdownNow();
  }

  @Test
  void aBodyThatStallsAfterTheHeadersTimesOutWithinTheDeadline() throws Exception {
    EzClassifyClient client = new EzClassifyClient(url(), RestClientCredentials.none(), DEADLINE);

    long started = System.nanoTime();
    EzClassifyClientException error = assertThrows(EzClassifyClientException.class, client::ping);

    assertWithinDeadline(started);
    assertTimedOut(error);
    assertTrue(
        tornDown.await(5, TimeUnit.SECONDS), "the timed-out exchange keeps its connection open");
  }

  @Test
  void aContextOperationOverATransportWithNoTimeoutOfItsOwnTimesOut() {
    // HttpClient.newHttpClient() sets no timeout at all; the deadline still holds.
    EzClassifyContext context = context(HttpClient.newHttpClient(), DEADLINE);

    long started = System.nanoTime();
    EzClassifyClientException error =
        assertThrows(EzClassifyClientException.class, () -> context.scheme().process("H"));

    assertWithinDeadline(started);
    assertTimedOut(error);
  }

  @Test
  void headersThatNeverArriveTimeOutWithinTheDeadline() {
    EzClassifyContext context = context(EzClassifyContext.newHttpClient(), DEADLINE);

    long started = System.nanoTime();
    EzClassifyClientException error =
        assertThrows(EzClassifyClientException.class, context::resolve);

    assertWithinDeadline(started);
    assertTimedOut(error);
  }

  @Test
  void interruptingAStalledCallReportsTheInterruptNotATimeout() throws Exception {
    EzClassifyContext context =
        context(EzClassifyContext.newHttpClient(), EzClassifyContext.REQUEST_TIMEOUT);
    Outcome outcome = new Outcome();
    Thread caller = outcome.start(() -> context.scheme().process("H"));

    awaitStall();
    caller.interrupt();
    caller.join(2000);

    assertFalse(caller.isAlive(), "the interrupted call is still waiting");
    EzClassifyClientException error =
        assertInstanceOf(EzClassifyClientException.class, outcome.failure.get());
    assertInstanceOf(InterruptedException.class, error.getCause());
    assertTrue(outcome.interrupted.get(), "the caller's interrupt flag was cleared");
    assertTrue(
        tornDown.await(5, TimeUnit.SECONDS), "the interrupted exchange keeps its connection open");
  }

  @Test
  void aCallerJoinedToASharedRequestCanBeInterrupted() throws Exception {
    ResponseCache cache = new ResponseCache();
    EzClassifyContext context =
        new EzClassifyContext(
            url(),
            "acme",
            "1.0.0",
            null,
            EzClassifyContext.newHttpClient(),
            ContextCacheOptions.defaults(),
            cache,
            Duration.ofSeconds(5));
    Outcome owner = new Outcome();
    Outcome joined = new Outcome();
    Thread first = owner.start(() -> context.scheme().process("H"));
    awaitStall();
    Thread second = joined.start(() -> context.scheme().process("H"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (cache.arrivals() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
    assertEquals(2, cache.arrivals(), "the second caller never joined the shared request");

    second.interrupt();
    second.join(1000);

    assertFalse(second.isAlive(), "the joined caller kept waiting after its interrupt");
    assertInstanceOf(
        InterruptedException.class,
        assertInstanceOf(EzClassifyClientException.class, joined.failure.get()).getCause());
    assertTrue(joined.interrupted.get(), "the joined caller's interrupt flag was cleared");
    assertTrue(first.isAlive(), "interrupting a joined caller ended the shared request");
    first.interrupt();
    first.join(2000);
  }

  @Test
  void aRequestDeadlineThatCannotBoundAWaitIsRefused() {
    for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new EzClassifyClient(url(), RestClientCredentials.none(), bad));
      assertThrows(
          IllegalArgumentException.class, () -> context(EzClassifyContext.newHttpClient(), bad));
    }
  }

  private EzClassifyContext context(HttpClient transport, Duration deadline) {
    return new EzClassifyContext(
        url(),
        "acme",
        "1.0.0",
        null,
        transport,
        ContextCacheOptions.defaults(),
        new ResponseCache(),
        deadline);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void assertWithinDeadline(long startedNanos) {
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    assertTrue(elapsed.compareTo(DEADLINE) >= 0, "failed before the deadline: " + elapsed);
    assertTrue(elapsed.compareTo(Duration.ofSeconds(3)) < 0, "outlived the deadline: " + elapsed);
  }

  private static void assertTimedOut(EzClassifyClientException error) {
    assertEquals(0, error.statusCode());
    assertInstanceOf(HttpTimeoutException.class, error.getCause());
    assertTrue(error.getMessage().contains("timed out"), error.getMessage());
  }

  private final CountDownLatch stalled = new CountDownLatch(1);

  private void awaitStall() throws InterruptedException {
    assertTrue(stalled.await(5, TimeUnit.SECONDS), "the server never received the request");
  }

  /** Answer the headers and the first byte, then hold the rest of the body. */
  private void stallAfterHeaders(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, 0);
    OutputStream body = exchange.getResponseBody();
    try {
      body.write('{');
      body.flush();
      stalled.countDown();
      // Keep writing until the client ends the exchange. A client that stopped reading but left
      // the connection open would let these writes succeed until the server gives up.
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(STALL_SECONDS);
      byte[] padding = " ".repeat(8192).getBytes(StandardCharsets.UTF_8);
      while (System.nanoTime() < until && release.getCount() > 0) {
        body.write(padding);
        body.flush();
        if (release.await(20, TimeUnit.MILLISECONDS)) break;
      }
    } catch (IOException closedByClient) {
      tornDown.countDown();
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /** Hold the request without answering the headers. */
  private void stallBeforeHeaders(HttpExchange exchange) throws IOException {
    stalled.countDown();
    try {
      release.await(STALL_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /** What a call made on its own thread ended with. */
  private static final class Outcome {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicBoolean interrupted = new AtomicBoolean();

    Thread start(Runnable call) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  call.run();
                } catch (Throwable error) {
                  failure.set(error);
                  interrupted.set(Thread.currentThread().isInterrupted());
                }
              });
      thread.setDaemon(true);
      thread.start();
      return thread;
    }
  }
}
