package com.ezclassify.rest.client;

import java.net.http.HttpRequest;

/**
 * Independent caller and Context tokens, or an opaque application credential for REST requests.
 *
 * @param accessToken bearer token identifying the caller, or {@code null} when authentication is
 *     disabled by the deployment
 * @param contextToken signed token authorizing Context operations, or {@code null} only when the
 *     deployment explicitly permits unauthenticated Context access
 * @param applicationCredential opaque application credential, or {@code null}; supply alone, as
 *     nonempty ASCII without controls or surrounding whitespace
 */
public record RestClientCredentials(
    String accessToken, String contextToken, String applicationCredential) {

  private static final String MALFORMED_APPLICATION_CREDENTIAL =
      "Application credential must be nonempty ASCII without controls or surrounding whitespace";

  /**
   * Create independent caller and Context credentials.
   *
   * @param accessToken bearer token identifying the caller, or {@code null}
   * @param contextToken signed Context token, or {@code null}
   * @throws IllegalArgumentException when a supplied token is blank
   */
  public RestClientCredentials(String accessToken, String contextToken) {
    this(accessToken, contextToken, null);
  }

  /**
   * Reject mixed or malformed credentials before any HTTP transport is used.
   *
   * @param accessToken bearer token identifying the caller, or {@code null}
   * @param contextToken signed Context authorization token, or {@code null}
   * @param applicationCredential opaque application credential, or {@code null}
   * @throws IllegalArgumentException when credentials are mixed or malformed
   */
  public RestClientCredentials {
    if (applicationCredential != null) {
      if (accessToken != null || contextToken != null) {
        throw new IllegalArgumentException(
            "Application credentials cannot be combined with caller or Context tokens");
      }
      if (applicationCredential.isEmpty()
          || applicationCredential.charAt(0) == ' '
          || applicationCredential.charAt(applicationCredential.length() - 1) == ' ') {
        throw new IllegalArgumentException(MALFORMED_APPLICATION_CREDENTIAL);
      }
      for (int i = 0; i < applicationCredential.length(); i++) {
        char character = applicationCredential.charAt(i);
        if (character < 0x20 || character > 0x7e) {
          throw new IllegalArgumentException(MALFORMED_APPLICATION_CREDENTIAL);
        }
      }
    }
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
        + ", applicationCredential="
        + present(applicationCredential)
        + "]";
  }

  private static String present(String token) {
    return token == null ? "none" : "set";
  }

  /** Whether any token will be put on the wire; drives the plaintext-transport check. */
  boolean present() {
    return accessToken != null || contextToken != null || applicationCredential != null;
  }

  /**
   * Apply ordinary caller and Context tokens, or the Context-only application credential.
   *
   * @param request request builder to receive authentication headers
   * @param contextPath whether this request addresses the Context route family
   */
  void apply(HttpRequest.Builder request, boolean contextPath) {
    if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
    if (contextPath && contextToken != null) {
      request.header("x-ezclassify-context-token", contextToken);
    }
    if (contextPath && applicationCredential != null) {
      request.header("X-EzClassify-Application-Grant", applicationCredential);
    }
  }
}
