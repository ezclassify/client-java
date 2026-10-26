package com.ezclassify.rest.client;

/**
 * Thrown when the server answers with success but the body is not what the contract promises: not
 * JSON, a value its reader cannot decode, or missing a member the contract marks required, at any
 * depth. The client never reads such a body as a plausible answer and never keeps it in its
 * response cache.
 *
 * <p>A request the caller built wrongly fails before it is sent, with {@link
 * IllegalArgumentException}; this exception always means the response was at fault. {@link
 * #statusCode()} is 0, because the server did not answer with an error status.
 */
public final class MalformedResponseException extends EzClassifyClientException {

  /** JSONPath of the member the contract required, or {@code null}. */
  private final String path;

  MalformedResponseException(String message) {
    this(message, (String) null);
  }

  MalformedResponseException(String message, String path) {
    super(message, null);
    this.path = path;
  }

  MalformedResponseException(String message, Throwable cause) {
    super(message, cause);
    this.path = null;
  }

  /**
   * The JSONPath of the member the contract required, such as {@code $.result.errors[0].code}.
   *
   * @return the path of the offending member, or {@code null} when the failure is not tied to one
   *     member, such as a body that is not JSON
   */
  public String path() {
    return path;
  }
}
