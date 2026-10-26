package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Credential handling rules on the caller-facing credentials record. */
class RestClientCredentialsTest {

  @Test
  void toStringDoesNotRenderTokenMaterial() {
    RestClientCredentials credentials = new RestClientCredentials("caller-token", "ctx-token");
    String rendered = credentials.toString();

    assertFalse(rendered.contains("caller-token"), rendered);
    assertFalse(rendered.contains("ctx-token"), rendered);
    assertEquals("RestClientCredentials[accessToken=set, contextToken=set]", rendered);
  }

  @Test
  void toStringDistinguishesAbsentTokens() {
    assertEquals(
        "RestClientCredentials[accessToken=none, contextToken=none]",
        RestClientCredentials.none().toString());
  }

  @Test
  void blankTokensAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new RestClientCredentials(" ", null));
    assertThrows(IllegalArgumentException.class, () -> new RestClientCredentials(null, " "));
  }
}
