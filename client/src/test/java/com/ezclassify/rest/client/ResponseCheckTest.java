package com.ezclassify.rest.client;

import static com.ezclassify.rest.client.ResponseShape.alternative;
import static com.ezclassify.rest.client.ResponseShape.any;
import static com.ezclassify.rest.client.ResponseShape.array;
import static com.ezclassify.rest.client.ResponseShape.map;
import static com.ezclassify.rest.client.ResponseShape.object;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The response check's rules on shapes the recorded corpus does not reach: union selection by
 * discriminator, members of the wrong kind, nullability, and how a member path is written. The
 * corpus-driven {@link RequiredMemberRefusalTest} covers the generated table end to end.
 */
class ResponseCheckTest {
  private static final ResponseShape TAGGED =
      any(
          false,
          alternative(object("kind a", ""), "kind", "first"),
          alternative(object("kind b", ""), "kind", "second"));

  private static final ResponseShape UNTAGGED =
      any(false, alternative(object("a", "")), alternative(object("b", "")));

  private static void accepts(ResponseShape shape, String body) {
    assertDoesNotThrow(() -> ResponseCheck.require(shape, JsonParser.parseString(body)), body);
  }

  private static MalformedResponseException refuses(ResponseShape shape, String body) {
    return assertThrows(
        MalformedResponseException.class,
        () -> ResponseCheck.require(shape, JsonParser.parseString(body)),
        body);
  }

  @Test
  void aUnionValueIsJudgedByTheAlternativeItNames() {
    accepts(TAGGED, "{\"kind\":\"first\",\"a\":1}");
    assertEquals("$.b", refuses(TAGGED, "{\"kind\":\"second\",\"a\":1}").path());
  }

  @Test
  void aVariantThisClientHasNotMetIsLeftToItsReader() {
    accepts(TAGGED, "{\"kind\":\"third\"}");
  }

  @Test
  void aValueNamingNoAlternativeMustSatisfyOneOfThem() {
    accepts(UNTAGGED, "{\"b\":1}");
    assertEquals("$.a", refuses(UNTAGGED, "{\"c\":1}").path());
  }

  @Test
  void anOptionalContainerOfTheWrongKindIsLeftToItsReaderButARequiredOneIsRefused() {
    ResponseShape shape = object("items", "", "items", array(null), "extra", array(null));
    accepts(shape, "{\"items\":[],\"extra\":{}}");
    MalformedResponseException error = refuses(shape, "{\"items\":{}}");
    assertEquals("$.items", error.path());
    assertEquals("Response member $.items must be an array", error.getMessage());
  }

  @Test
  void nullIsAcceptedOnlyWhereTheContractAllowsIt() {
    ResponseShape shape = object("open closed", "open");
    accepts(shape, "{\"open\":null,\"closed\":1}");
    assertEquals("$.closed", refuses(shape, "{\"open\":null,\"closed\":null}").path());
  }

  @Test
  void aMemberThatIsNotAnIdentifierIsWrittenInBrackets() {
    ResponseShape shape = map(object("id", ""));
    assertEquals(
        "$['a b\\\\c\\'d'].id", refuses(shape, "{\"a b\\\\c'd\":{}}").path(), "quoted and escaped");
    assertEquals(
        "$.plain[1].id",
        refuses(
                object("plain", "", "plain", array(object("id", ""))),
                "{\"plain\":[{\"id\":1},{}]}")
            .path());
  }

  @Test
  void anOperationTheContractDoesNotDeclareIsAClientBug() {
    assertThrows(
        IllegalStateException.class,
        () -> ResponseCheck.require("GET /v1/nothing", JsonParser.parseString("{}")));
  }
}
