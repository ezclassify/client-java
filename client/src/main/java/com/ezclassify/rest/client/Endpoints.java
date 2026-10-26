package com.ezclassify.rest.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Base-URL validation shared by the server client and the Context handle.
 *
 * <p>Two rules, both applied at construction so a malformed endpoint fails where the caller
 * configured it rather than on the first request:
 *
 * <ul>
 *   <li>the base URL parses as an absolute {@code http} or {@code https} URI with a host;
 *   <li>credentials never travel over plaintext {@code http} to a non-loopback host.
 * </ul>
 */
final class Endpoints {

  /** One octet of a dotted-quad IPv4 literal, bounded to 0-255. */
  private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

  /** Dotted-quad literals in the IPv4 loopback block {@code 127.0.0.0/8}. */
  private static final Pattern IPV4_LOOPBACK =
      Pattern.compile("127\\." + OCTET + "\\." + OCTET + "\\." + OCTET);

  private Endpoints() {}

  /**
   * Normalize a base URL and enforce the transport rules above.
   *
   * @param baseUrl caller-supplied REST API base URL
   * @param carriesCredentials whether requests to this endpoint will carry a token
   * @return the base URL with any trailing slash removed
   * @throws IllegalArgumentException when the URL is blank, is not an absolute HTTP(S) URL, or
   *     would send credentials in the clear
   */
  static String requireTransport(String baseUrl, boolean carriesCredentials) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be empty");
    }
    URI uri;
    try {
      uri = new URI(baseUrl.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("baseUrl is not a valid URL: " + baseUrl, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("baseUrl must be an absolute http or https URL");
    }
    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      throw new IllegalArgumentException("baseUrl must include a host");
    }
    if (carriesCredentials && scheme.equals("http") && !isLoopback(uri.getHost())) {
      throw new IllegalArgumentException(
          "baseUrl must use https when credentials are supplied; plain http is accepted only for a"
              + " loopback development endpoint");
    }
    String trimmed = baseUrl.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  /**
   * Loopback hosts a development deployment legitimately serves over plaintext. {@code InetAddress}
   * is deliberately not consulted: a DNS lookup at construction would make the check
   * network-dependent and resolvable to a loopback address by an outside name.
   *
   * <p>The IPv4 form is matched as a complete dotted-quad literal. A prefix test would also accept
   * a registered name that merely starts with {@code 127.}, which is an ordinary remote host.
   */
  private static boolean isLoopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
    return h.equals("localhost") || h.equals("::1") || IPV4_LOOPBACK.matcher(h).matches();
  }
}
