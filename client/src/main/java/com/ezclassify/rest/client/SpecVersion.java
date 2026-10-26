package com.ezclassify.rest.client;

/**
 * Engine spec version of the vendored contract bundle this client was built against.
 *
 * <p>This equals the rest-api version this client is intended to communicate with. A mismatch
 * between this constant and the wire version returned by {@link EzClassifyClient#ping()} indicates
 * a version skew that may cause subtle behavior differences.
 */
public final class SpecVersion {

  /**
   * Engine spec version this client was built against, matching the version recorded in {@code
   * vendor/spec/MANIFEST.json}. Stamped by release automation; do not edit by hand.
   */
  public static final String CLIENT_SPEC_VERSION = "1.0.0";

  private SpecVersion() {}
}
