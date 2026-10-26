package com.ezclassify.rest.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared HTTP transport for the client surface: send a request and map transport and HTTP-status
 * failures to {@link EzClassifyClientException}. Sending and status checking are separate steps so
 * callers can read response headers (e.g. the resolved-version header) before failing on the
 * status.
 */
final class ClientHttp {

  static final int MAX_RESPONSE_BYTES = 32 << 20;

  private static final int TOO_MANY_REQUESTS = 429;
  private static final int NOT_FOUND = 404;
  private static final String DENIAL_MEMBER = "error";
  private static final String DENIAL_VALUE = "not_found";

  private ClientHttp() {}

  /**
   * Send the request and return the raw response, mapping transport failures to {@link
   * EzClassifyClientException}. Does not inspect the status code; follow up with {@link
   * #requireSuccess} for fail-on-error semantics.
   *
   * <p>{@code timeout} bounds the whole exchange: connecting, the response headers and the full
   * body. {@link HttpRequest#timeout} alone stops at the headers, so a server that answers them and
   * then stalls the body would hold the caller indefinitely. When the deadline passes, the exchange
   * is cancelled, which closes its connection, and the failure's cause is an {@link
   * HttpTimeoutException}. An interrupt also cancels the exchange; the interrupt flag stays set so
   * the caller still sees it.
   *
   * @param client the HTTP client to send with
   * @param request the fully built request
   * @param path the request path, used in the failure message
   * @param timeout how long the whole exchange may take
   * @return the raw HTTP response
   * @throws EzClassifyClientException if the request could not be completed in time
   */
  static HttpResponse<String> send(
      HttpClient client, HttpRequest request, String path, Duration timeout) {
    CompletableFuture<HttpResponse<String>> exchange =
        client.sendAsync(request, bodyHandler(MAX_RESPONSE_BYTES));
    try {
      return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      exchange.cancel(true);
      String message = "HTTP request to " + path + " timed out after " + timeout.toMillis() + " ms";
      HttpTimeoutException cause = new HttpTimeoutException(message);
      cause.initCause(e);
      throw new EzClassifyClientException(message, cause);
    } catch (InterruptedException e) {
      exchange.cancel(true);
      // Restore the flag so callers running under an executor still observe the interrupt.
      Thread.currentThread().interrupt();
      throw new EzClassifyClientException("HTTP request to " + path + " was interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof Error error) throw error;
      throw new EzClassifyClientException(
          "HTTP request to " + path + " failed: " + cause.getMessage(), cause);
    }
  }

  /**
   * Refuse a request deadline that could not bound a wait.
   *
   * @param timeout the deadline to check
   * @return {@code timeout}
   * @throws IllegalArgumentException if it is {@code null}, zero or negative
   */
  static Duration requireTimeout(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("request timeout must be positive");
    }
    return timeout;
  }

  /** Create a bounded subscriber for each response. */
  static HttpResponse.BodyHandler<String> bodyHandler(int maxBytes) {
    if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
    return info -> new BoundedBodySubscriber(info.headers(), maxBytes);
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> body = new CompletableFuture<>();
    private final ByteArrayOutputStream accepted;
    private final byte[] scratch = new byte[8192];
    private final Charset charset;
    private final int maxBytes;
    private final long declaredLength;
    private Flow.Subscription subscription;

    BoundedBodySubscriber(HttpHeaders headers, int maxBytes) {
      this.maxBytes = maxBytes;
      this.accepted = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
      this.charset = responseCharset(headers);
      this.declaredLength = responseLength(headers);
    }

    @Override
    public CompletionStage<String> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription next) {
      if (subscription != null) {
        next.cancel();
        return;
      }
      subscription = next;
      if (declaredLength > maxBytes) {
        fail();
      } else {
        subscription.request(1);
      }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) return;
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maxBytes - accepted.size()) {
          fail();
          return;
        }
        while (buffer.hasRemaining()) {
          int count = Math.min(buffer.remaining(), scratch.length);
          buffer.get(scratch, 0, count);
          accepted.write(scratch, 0, count);
        }
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      body.complete(new String(accepted.toByteArray(), charset));
    }

    private void fail() {
      body.completeExceptionally(new IOException("response body exceeds " + maxBytes + " bytes"));
      subscription.cancel();
    }
  }

  private static long responseLength(HttpHeaders headers) {
    try {
      return headers.firstValueAsLong("Content-Length").orElse(-1);
    } catch (NumberFormatException ignored) {
      // A malformed size hint cannot bypass the streamed byte limit.
      return -1;
    }
  }

  /** Match the JDK string handler's Content-Type charset selection and UTF-8 fallback. */
  private static Charset responseCharset(HttpHeaders headers) {
    String contentType = headers.firstValue("Content-Type").orElse("");
    for (String part : contentType.split(";")) {
      int equals = part.indexOf('=');
      if (equals < 0 || !part.substring(0, equals).trim().equalsIgnoreCase("charset")) continue;
      String name = part.substring(equals + 1).trim();
      if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
        name = name.substring(1, name.length() - 1);
      }
      try {
        return Charset.forName(name);
      } catch (IllegalArgumentException ignored) {
        return StandardCharsets.UTF_8;
      }
    }
    return StandardCharsets.UTF_8;
  }

  /**
   * Whether the response is the runtime's constant denial: a 404 whose body is exactly {@code
   * {"error":"not_found"}}. A deployment that authenticates answers every request it refuses this
   * way, so the status alone does not say a resource is missing. A 404 that names the missing
   * resource carries more members and is not a denial.
   *
   * @param response the response to classify
   * @return {@code true} when the status and body are the denial shape
   */
  static boolean isDenial(HttpResponse<String> response) {
    if (response.statusCode() != NOT_FOUND) return false;
    try {
      JsonObject body = Json.parseObject(response.body());
      return body.size() == 1
          && body.has(DENIAL_MEMBER)
          && body.get(DENIAL_MEMBER).isJsonPrimitive()
          && body.get(DENIAL_MEMBER).getAsJsonPrimitive().isString()
          && DENIAL_VALUE.equals(body.get(DENIAL_MEMBER).getAsString());
    } catch (RuntimeException notAnObject) {
      return false;
    }
  }

  /**
   * Fail on HTTP error statuses.
   *
   * @param response the response to check
   * @throws EzClassifyClientException if the status code is 400 or above
   */
  static void requireSuccess(HttpResponse<String> response) {
    if (response.statusCode() >= 400) {
      String code = null;
      String detail = null;
      String message = null;
      Duration retryAfter = null;
      try {
        var body = Json.parseObject(response.body());
        message = Json.isNull(body, "error") ? null : Json.optString(body, "error");
        code = Json.isNull(body, "code") ? null : Json.optString(body, "code");
        detail = Json.isNull(body, "detail") ? null : Json.optString(body, "detail");
        if (response.statusCode() == TOO_MANY_REQUESTS) retryAfter = bodyRetryAfter(body);
      } catch (RuntimeException ignored) {
        // The status remains authoritative. Do not echo an unstructured body that may contain
        // sensitive request data or internal diagnostics.
      }
      if (response.statusCode() == TOO_MANY_REQUESTS) {
        Duration header = headerRetryAfter(response.headers());
        if (header != null) retryAfter = header;
      }
      throw new EzClassifyClientException(response.statusCode(), message, code, detail, retryAfter);
    }
  }

  /**
   * The {@code Retry-After} header as delta-seconds, the form the contract declares. A value that
   * is not a non-negative whole number, such as an HTTP date, is ignored so the body's copy can
   * answer.
   */
  private static Duration headerRetryAfter(HttpHeaders headers) {
    String value = headers.firstValue("Retry-After").map(String::trim).orElse("");
    if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
    try {
      return Duration.ofSeconds(Long.parseLong(value));
    } catch (NumberFormatException tooLarge) {
      return null;
    }
  }

  /** The rate-limit body's {@code retryAfter} seconds, or {@code null} when absent or unusable. */
  private static Duration bodyRetryAfter(JsonObject body) {
    JsonElement seconds = body.get("retryAfter");
    if (seconds == null || !seconds.isJsonPrimitive() || !seconds.getAsJsonPrimitive().isNumber()) {
      return null;
    }
    double value = seconds.getAsDouble();
    if (!(value >= 0)) return null;
    return Duration.ofMillis((long) Math.ceil(value * 1000));
  }
}
