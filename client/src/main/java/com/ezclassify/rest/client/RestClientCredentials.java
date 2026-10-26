package com.ezclassify.rest.client;

import java.net.http.HttpRequest;

/**
 * Independent caller-identity and Context-authorization credentials for REST requests.
 *
 * @param accessToken bearer token identifying the caller, or {@code null} when authentication is
 *     disabled by the deployment
 * @param contextToken signed token authorizing Context operations, or {@code null} only when the
 *     deployment explicitly permits unauthenticated Context access
 */
public record RestClientCredentials(String accessToken, String contextToken) {

  /**
   * Reject blank credentials, which would otherwise send malformed authorization headers.
   *
   * @param accessToken bearer token identifying the caller, or {@code null}
   * @param contextToken signed Context authorization token, or {@code null}
   */
  public RestClientCredentials {
    if (accessToken != null && accessToken.isBlank()) {
      throw new IllegalArgumentException("accessToken must be null or non-blank");
    }
    if (contextToken != null && contextToken.isBlank()) {
      throw new IllegalArgumentException("contextToken must be null or non-blank");
    }
  }

  /**
   * Return credentials for a REST deployment explicitly configured without authentication.
   *
   * @return credentials that add no authentication headers
   */
  public static RestClientCredentials none() {
    return new RestClientCredentials(null, null);
  }

  /**
   * Render the credential shape without the secrets. The token values are never included, so a host
   * that logs this record or interpolates it into a diagnostic cannot leak them.
   *
   * @return the presence of each token, with no token material
   */
  @Override
  public final String toString() {
    return "RestClientCredentials[accessToken="
        + present(accessToken)
        + ", contextToken="
        + present(contextToken)
        + "]";
  }

  private static String present(String token) {
    return token == null ? "none" : "set";
  }

  /** Whether any token will be put on the wire; drives the plaintext-transport check. */
  boolean present() {
    return accessToken != null || contextToken != null;
  }

  /**
   * Apply caller identity and, for Context paths, the independent Context authorization.
   *
   * @param request request builder to receive authentication headers
   * @param contextPath whether this request addresses the Context route family
   */
  void apply(HttpRequest.Builder request, boolean contextPath) {
    if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
    if (contextPath && contextToken != null) {
      request.header("x-ezclassify-context-token", contextToken);
    }
  }
}
