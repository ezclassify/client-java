package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** HTTP controls for the shared client response-size boundary. */
@SuppressWarnings("restriction")
class ClientHttpResponseBoundTest {

  @Test
  void defaultLimitMatchesThePublishedSdkResponseLimit() {
    assertEquals(32 << 20, ClientHttp.MAX_RESPONSE_BYTES);
  }

  @Test
  void publicPingRejectsChunkedResponseLargerThanTheDefaultLimit() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/health",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 0);
          byte[] chunk = new byte[8192];
          Arrays.fill(chunk, (byte) 'x');
          try (var output = exchange.getResponseBody()) {
            output.write(
                "{\"status\":\"ok\",\"engineVersion\":\"1.0.0\",\"engineSha\":\"x\",\"gitTag\":\"v1.0.0\",\"openapiVersion\":\"1.0.0\",\"abiVersion\":\"v1\",\"padding\":\""
                    .getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 4096; i++) output.write(chunk);
            output.write("\"}".getBytes(StandardCharsets.UTF_8));
          } catch (IOException expectedWhenClientCancels) {
            // The bounded subscriber cancels while the server is still writing.
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (EzClassifyClient client =
        new EzClassifyClient("http://127.0.0.1:" + server.getAddress().getPort())) {
      EzClassifyClientException error = assertThrows(EzClassifyClientException.class, client::ping);
      assertTrue(error.getMessage().contains("response body exceeds"), error.getMessage());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void publicPingAcceptsAResponseExactlyAtTheDefaultLimit() throws Exception {
    byte[] prefix =
        "{\"status\":\"ok\",\"engineVersion\":\"1.0.0\",\"engineSha\":\"x\",\"gitTag\":\"v1.0.0\",\"openapiVersion\":\"1.0.0\",\"abiVersion\":\"v1\",\"padding\":\""
            .getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/health",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, ClientHttp.MAX_RESPONSE_BYTES);
          byte[] chunk = new byte[8192];
          Arrays.fill(chunk, (byte) 'x');
          try (var output = exchange.getResponseBody()) {
            output.write(prefix);
            int remaining = ClientHttp.MAX_RESPONSE_BYTES - prefix.length - suffix.length;
            while (remaining > 0) {
              int next = Math.min(remaining, chunk.length);
              output.write(chunk, 0, next);
              remaining -= next;
            }
            output.write(suffix);
          }
        });
    server.start();
    try (EzClassifyClient client =
        new EzClassifyClient("http://127.0.0.1:" + server.getAddress().getPort())) {
      assertEquals("1.0.0", client.ping().engineVersion());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void anUnderstatedLengthCannotBypassTheStreamLimit() {
    HttpResponse.BodySubscriber<String> subscriber = subscriber(1024, "1");
    CountingSubscription subscription = new CountingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[512])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[512])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[512])));
    CompletionException error =
        assertThrows(
            CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    assertInstanceOf(IOException.class, error.getCause());
    assertTrue(error.getCause().getMessage().contains("response body exceeds"));
    assertEquals(3, subscription.requests);
    assertTrue(subscription.cancelled);
  }

  @Test
  void aMalformedLengthCannotBypassTheStreamLimit() {
    HttpResponse.BodySubscriber<String> subscriber = subscriber(1024, "not-a-number");
    CountingSubscription subscription = new CountingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1024])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
    CompletionException error =
        assertThrows(
            CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    assertInstanceOf(IOException.class, error.getCause());
    assertEquals(2, subscription.requests);
    assertTrue(subscription.cancelled);
  }

  @Test
  void aDeclaredOversizeIsRejectedBeforeRequestingBodyBytes() {
    HttpResponse.BodySubscriber<String> subscriber = subscriber(1024, "1025");
    CountingSubscription subscription = new CountingSubscription();
    subscriber.onSubscribe(subscription);
    assertEquals(0, subscription.requests);
    assertTrue(subscription.cancelled);
    assertThrows(
        CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
  }

  @Test
  void anExactLimitBodyAndSplitUtf8AreReturnedWithoutTruncation() {
    HttpResponse.BodySubscriber<String> subscriber = subscriber(1024, null);
    CountingSubscription subscription = new CountingSubscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1022])));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {(byte) 0xc3})));
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {(byte) 0xa9})));
    subscriber.onComplete();
    assertEquals(
        "\u0000".repeat(1022) + "\u00e9", subscriber.getBody().toCompletableFuture().join());
    assertFalse(subscription.cancelled);
  }

  @Test
  void boundedTransportRetainsStatusHeadersAndDeclaredCharset() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/status",
        exchange -> {
          byte[] body = "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
          exchange.getResponseHeaders().set("X-EzClassify-Resolved-Version", "2.0.0");
          exchange.sendResponseHeaders(418, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/status");
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      var response =
          ClientHttp.send(
              HttpClient.newHttpClient(), request, "/status", EzClassifyContext.REQUEST_TIMEOUT);
      assertEquals(418, response.statusCode());
      assertEquals(
          "2.0.0", response.headers().firstValue("X-EzClassify-Resolved-Version").orElseThrow());
      assertEquals("caf\u00e9", response.body());
      assertEquals(
          418,
          assertThrows(EzClassifyClientException.class, () -> ClientHttp.requireSuccess(response))
              .statusCode());
    } finally {
      server.stop(0);
    }
  }

  private static HttpResponse.BodySubscriber<String> subscriber(int maxBytes, String length) {
    Map<String, List<String>> values =
        length == null ? Map.of() : Map.of("Content-Length", List.of(length));
    HttpHeaders headers = HttpHeaders.of(values, (name, value) -> true);
    HttpResponse.ResponseInfo info =
        new HttpResponse.ResponseInfo() {
          @Override
          public int statusCode() {
            return 200;
          }

          @Override
          public HttpHeaders headers() {
            return headers;
          }

          @Override
          public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
          }
        };
    return ClientHttp.bodyHandler(maxBytes).apply(info);
  }

  private static final class CountingSubscription implements Flow.Subscription {
    int requests;
    boolean cancelled;

    @Override
    public void request(long n) {
      requests++;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
