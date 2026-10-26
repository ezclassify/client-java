package com.ezclassify.rest.client;

import java.time.Duration;

/**
 * Thrown when the EzClassify REST API returns a non-2xx response or a connection attempt fails
 * outright, and, as {@link MalformedResponseException}, when a success response is not what the
 * contract promises. The HTTP status code, when one was received, is accessible via {@link
 * #statusCode()}; it is 0 for failures that carry no error status.
 *
 * <p>A call that runs past its deadline fails with status 0 and a {@link
 * java.net.http.HttpTimeoutException} cause. A call ended by interrupting its thread fails with
 * status 0 and an {@link InterruptedException} cause, and the thread's interrupt flag stays set.
 */
public class EzClassifyClientException extends RuntimeException {

  /** HTTP response status, or zero when no response was received. */
  private final int statusCode;

  /** Stable machine-readable server error code, when supplied. */
  private final String code;

  /** Stable sanitized server error detail, when supplied. */
  private final String detail;

  /** How long the server asks the caller to wait before retrying, when it said. */
  private final Duration retryAfter;

  /**
   * HTTP error with the public REST error text and stable classification fields.
   *
   * @param statusCode HTTP status
   * @param message public sanitized error text from the {@code error} field
   * @param code stable server error code, when present
   * @param detail stable sanitized detail, when present
   * @param retryAfter how long the server asks the caller to wait before retrying, or {@code null}
   *     when it did not say
   */
  public EzClassifyClientException(
      int statusCode, String message, String code, String detail, Duration retryAfter) {
    super(
        message == null || message.isEmpty()
            ? "REST API error " + statusCode + (code == null ? "" : " (" + code + ")")
            : message);
    this.statusCode = statusCode;
    this.code = code;
    this.detail = detail;
    this.retryAfter = retryAfter;
  }

  /**
   * Connection-level failure: no HTTP status is available.
   *
   * @param message detail message
   * @param cause the underlying I/O or interrupt exception
   */
  public EzClassifyClientException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.code = null;
    this.detail = null;
    this.retryAfter = null;
  }

  /**
   * HTTP status code returned by the server, or {@code 0} for connection errors.
   *
   * @return the HTTP status code, or {@code 0} when no response was received
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * Returns the stable server error code, or {@code null} when unavailable.
   *
   * @return stable error code, or {@code null}
   */
  public String code() {
    return code;
  }

  /**
   * Returns the stable sanitized error detail, or {@code null} when unavailable.
   *
   * @return sanitized error detail, or {@code null}
   */
  public String detail() {
    return detail;
  }

  /**
   * How long the server asks the caller to wait before sending the request again. Only a {@code
   * 429} rate-limit refusal carries it: the {@code Retry-After} header when present, otherwise the
   * body's {@code retryAfter}. The client never waits or retries on its own; the caller decides
   * whether and when to send the request again.
   *
   * @return the wait the server asked for, or {@code null} when the error is not a rate-limit
   *     refusal or the server gave no usable value
   */
  public Duration retryAfter() {
    return retryAfter;
  }
}
