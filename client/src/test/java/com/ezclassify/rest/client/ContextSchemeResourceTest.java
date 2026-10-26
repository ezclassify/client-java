package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ezclassify.rest.contract.CategoryView;
import com.ezclassify.rest.contract.FormatSyntaxType;
import com.ezclassify.rest.contract.FormatView;
import com.ezclassify.rest.contract.LabelPresetView;
import com.ezclassify.rest.contract.ProcessingRole;
import com.ezclassify.rest.contract.RenderFormatMode;
import com.ezclassify.rest.contract.Rollup;
import com.ezclassify.rest.contract.SchemeGrammar;
import com.ezclassify.rest.contract.SchemeScope;
import com.ezclassify.rest.contract.SchemeSummary;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The scheme resource reads: summary, concepts, categories, formats, grammar, scopes and label
 * presets. They answer the bare resource, send only the query the contract declares, and reach the
 * server on every call.
 */
class ContextSchemeResourceTest {
  private static final OpenApiSchemas SPEC = OpenApiSchemas.load();
  private static final String PREFIX = "/v1/ctx/district/1.0.0";
  private static final String CONTRACT_PREFIX = "/v1/ctx/{ctxId}/{version}/";

  private static final String SUMMARY =
      "{\"id\":\"district\",\"version\":\"1.0.0\",\"name\":\"District\",\"description\":\"A district scheme.\","
          + "\"defaultScope\":\"banner\",\"scopeLabel\":\"Scope\",\"defaultRenderFormat\":\"long\","
          + "\"roleAware\":true,"
          + "\"vocabulary\":{\"whole\":{\"singular\":\"banner\",\"plural\":\"banners\"}},"
          + "\"referenceSurfaceLabel\":{\"singular\":\"Handbook\",\"plural\":\"Handbooks\"},"
          + "\"contextFormats\":{\"whole\":[\"long\"],\"part\":[\"short\"]}}";

  private static final String CONCEPTS =
      "[{\"id\":\"PUBLIC\",\"name\":\"Public\",\"views\":{\"default\":\"PUBLIC\"},\"categories\":[\"level\"],"
          + "\"parents\":[],\"position\":0,\"aliases\":[],\"rank\":-100}]";

  private static final String CATEGORIES =
      "[{\"id\":\"level\",\"label\":\"Level\",\"required\":true,\"single\":true,\"rollup\":\"max\","
          + "\"sort\":\"numeric\",\"scopes\":[\"banner\"],\"position\":0,\"hierarchyRoot\":true,"
          + "\"defaultConcept\":\"PUBLIC\"},"
          + "{\"id\":\"caveats\",\"label\":\"Caveats\",\"required\":false,\"single\":false,\"rollup\":\"union\","
          + "\"sort\":[\"position\",\"alphabetical\"],\"positionOverrides\":{\"DNF\":2}}]";

  private static final String FORMATS =
      "[{\"id\":\"long\",\"surface\":\"banner\",\"mode\":\"inline\",\"categoryOrder\":[\"level\",\"caveats\"],"
          + "\"separator\":\"//\",\"categorySeparators\":{\"level\":\"\",\"caveats\":\"/\"},"
          + "\"separatorBefore\":{\"caveats\":\"//\"},"
          + "\"categoryEach\":{\"caveats\":{\"prefix\":\"[\",\"suffix\":\"]\"}},"
          + "\"defaultFor\":[\"whole\"],\"role\":\"whole\",\"syntax\":{\"type\":\"xml-attributes\"}}]";

  private static final String GRAMMAR = "{\"caseSensitive\":false,\"stripWhitespace\":true}";

  private static final String SCOPES =
      "[{\"id\":\"banner\",\"label\":\"Banner\"},{\"id\":\"portion\",\"label\":\"Portion\"}]";

  private static final String LABEL_PRESETS =
      "[{\"id\":\"sensitive\",\"label\":\"Sensitive\",\"description\":\"Shorthand.\",\"scope\":\"banner\"}]";

  /** One read: its route under the Context prefix, a canned answer, and how it is called. */
  private record Read(
      String name,
      String route,
      String answer,
      boolean takesScheme,
      Function<ContextScheme, Object> call,
      Function<ContextScheme, Object> callOfScheme) {}

  private static final List<Read> READS =
      List.of(
          new Read("summary", "scheme", SUMMARY, true, s -> s.summary(), s -> s.summary("src/a")),
          new Read(
              "concepts",
              "scheme/concepts",
              CONCEPTS,
              true,
              s -> s.concepts(),
              s -> s.concepts("src/a")),
          new Read(
              "categories",
              "scheme/categories",
              CATEGORIES,
              true,
              s -> s.categories(),
              s -> s.categories("src/a")),
          new Read(
              "formats",
              "scheme/formats",
              FORMATS,
              true,
              s -> s.formats(),
              s -> s.formats("src/a")),
          new Read(
              "grammar",
              "scheme/grammar",
              GRAMMAR,
              true,
              s -> s.grammar(),
              s -> s.grammar("src/a")),
          new Read(
              "scopes", "scheme/scopes", SCOPES, true, s -> s.scopes(), s -> s.scopes("src/a")),
          new Read(
              "label-presets",
              "scheme/label-presets",
              LABEL_PRESETS,
              false,
              s -> s.labelPresets(),
              null));

  static Stream<Arguments> reads() {
    return READS.stream().map(read -> Arguments.of(read.name(), read));
  }

  static Stream<Arguments> selectableReads() {
    return READS.stream().filter(Read::takesScheme).map(read -> Arguments.of(read.name(), read));
  }

  /** A stub that serves every canned answer under the Context prefix. */
  private static ClientStubServer stub() throws Exception {
    Map<String, String> routes = new java.util.HashMap<>();
    routes.put(PREFIX, ClientStubServer.description(PREFIX));
    for (Read read : READS) routes.put(PREFIX + "/" + read.route(), read.answer());
    return new ClientStubServer(routes, "1.0.0");
  }

  private static ContextScheme scheme(ClientStubServer stub) {
    return new EzClassifyContext(stub.url(), "district", "1.0.0", null).scheme();
  }

  @Test
  void everyCannedAnswerIsOneTheContractAllows() {
    List<String> failures = new ArrayList<>();
    for (Read read : READS) {
      for (String error :
          SPEC.validateResponse(
              "get", CONTRACT_PREFIX + read.route(), JsonParser.parseString(read.answer()))) {
        failures.add(read.name() + " " + error);
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("reads")
  void aReadIsOneGetToItsRouteWithNoQueryForThePrimaryScheme(String name, Read read)
      throws Exception {
    try (ClientStubServer stub = stub()) {
      assertNotNull(read.call().apply(scheme(stub)));
      assertEquals(1, stub.requestCount(), name);
      assertEquals("GET", stub.lastRequest().method(), name);
      assertEquals(PREFIX + "/" + read.route(), stub.lastRequest().path(), name);
      assertNull(stub.lastRequest().query(), name);
      assertEquals("", stub.lastRequest().body(), name);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("selectableReads")
  void aNamedSchemeIsTheOnlyQueryParameterAndIsEncoded(String name, Read read) throws Exception {
    try (ClientStubServer stub = stub()) {
      assertNotNull(read.callOfScheme().apply(scheme(stub)));
      assertEquals("scheme=src%2Fa", stub.lastRequest().query(), name);
      assertEquals(PREFIX + "/" + read.route(), stub.lastRequest().path(), name);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("selectableReads")
  void aBlankSchemeIsRefusedBeforeAnyRequestIsSent(String name, Read read) throws Exception {
    try (ClientStubServer stub = stub()) {
      ContextScheme scheme = scheme(stub);
      for (String blank : new String[] {"", "  "}) {
        Function<ContextScheme, Object> call = blankCall(name, blank);
        assertThrows(IllegalArgumentException.class, () -> call.apply(scheme), name);
      }
      assertEquals(0, stub.requestCount(), name);
    }
  }

  private static Function<ContextScheme, Object> blankCall(String name, String blank) {
    return switch (name) {
      case "summary" -> s -> s.summary(blank);
      case "concepts" -> s -> s.concepts(blank);
      case "categories" -> s -> s.categories(blank);
      case "formats" -> s -> s.formats(blank);
      case "grammar" -> s -> s.grammar(blank);
      case "scopes" -> s -> s.scopes(blank);
      default -> throw new AssertionError(name);
    };
  }

  /** The cache holds operations; a resource read goes to the server every time, pinned or not. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("reads")
  void aReadReachesTheServerOnEveryCallEvenUnderAConcretePin(String name, Read read)
      throws Exception {
    try (ClientStubServer stub = stub()) {
      ContextScheme scheme = scheme(stub);
      read.call().apply(scheme);
      read.call().apply(scheme);
      read.call().apply(scheme);
      assertEquals(3, stub.requestCount(), name);
    }
  }

  @Test
  void summaryDecodesTheSchemeIdentityAndPresentation() throws Exception {
    try (ClientStubServer stub = stub()) {
      SchemeSummary summary = scheme(stub).summary();
      assertEquals("district", summary.id());
      assertEquals("1.0.0", summary.version());
      assertEquals("District", summary.name());
      assertEquals("banner", summary.defaultScope());
      assertEquals("long", summary.defaultRenderFormat());
      assertEquals(Boolean.TRUE, summary.roleAware());
      assertEquals("banners", summary.vocabulary().get("whole").plural());
      assertEquals("Handbooks", summary.referenceSurfaceLabel().plural());
      assertEquals(List.of("short"), summary.contextFormats().part());
    }
  }

  @Test
  void categoriesDecodeInDeclaredOrderWithTheirRollupAndSortAsSent() throws Exception {
    try (ClientStubServer stub = stub()) {
      List<CategoryView> categories = scheme(stub).categories();
      assertEquals(List.of("level", "caveats"), categories.stream().map(CategoryView::id).toList());
      CategoryView level = categories.get(0);
      assertEquals(Rollup.MAX, level.rollup());
      assertEquals(Boolean.TRUE, level.required());
      assertEquals(List.of("banner"), level.scopes());
      assertEquals("numeric", level.sort());
      assertEquals("PUBLIC", level.defaultConcept());
      CategoryView caveats = categories.get(1);
      assertEquals(Rollup.UNION, caveats.rollup());
      assertEquals(List.of("position", "alphabetical"), caveats.sort());
      assertEquals(2.0, caveats.positionOverrides().get("DNF"));
    }
  }

  @Test
  void formatsDecodeTheLayoutAndTheSyntaxTheTextIsFramedIn() throws Exception {
    try (ClientStubServer stub = stub()) {
      FormatView format = scheme(stub).formats().get(0);
      assertEquals("long", format.id());
      assertEquals("banner", format.surface());
      assertEquals(RenderFormatMode.INLINE, format.mode());
      assertEquals(List.of("level", "caveats"), format.categoryOrder());
      assertEquals("/", format.categorySeparators().get("caveats"));
      assertEquals("//", format.separatorBefore().get("caveats"));
      assertEquals("[", format.categoryEach().get("caveats").prefix());
      assertEquals(List.of(ProcessingRole.WHOLE), format.defaultFor());
      assertEquals(FormatSyntaxType.XML_ATTRIBUTES, format.syntax().type());
    }
  }

  @Test
  void grammarDecodesTheRecognitionPolicy() throws Exception {
    try (ClientStubServer stub = stub()) {
      SchemeGrammar grammar = scheme(stub).grammar();
      assertEquals(false, grammar.caseSensitive());
      assertEquals(true, grammar.stripWhitespace());
    }
  }

  @Test
  void scopesKeepDeclarationOrder() throws Exception {
    try (ClientStubServer stub = stub()) {
      List<SchemeScope> scopes = scheme(stub).scopes();
      assertEquals(List.of("banner", "portion"), scopes.stream().map(SchemeScope::id).toList());
      assertEquals("Portion", scopes.get(1).label());
    }
  }

  @Test
  void labelPresetsDecodeNameAndScopeOnly() throws Exception {
    try (ClientStubServer stub = stub()) {
      LabelPresetView preset = scheme(stub).labelPresets().get(0);
      assertEquals("sensitive", preset.id());
      assertEquals("Sensitive", preset.label());
      assertEquals("Shorthand.", preset.description());
      assertEquals("banner", preset.scope());
    }
  }

  @Test
  void aSchemeThatDeclaresNoScopesOrPresetsAnswersEmptyListsNotNull() throws Exception {
    try (ClientStubServer stub = stub()) {
      stub.setResponse(PREFIX + "/scheme/scopes", "[]");
      stub.setResponse(PREFIX + "/scheme/label-presets", "[]");
      assertEquals(List.of(), scheme(stub).scopes());
      assertEquals(List.of(), scheme(stub).labelPresets());
    }
  }

  @Test
  void aServerAheadOfThisBuildDecodesInsteadOfFailing() throws Exception {
    try (ClientStubServer stub = stub()) {
      stub.setResponse(
          PREFIX + "/scheme/formats",
          "[{\"id\":\"long\",\"surface\":\"banner\",\"mode\":\"inline\",\"categoryOrder\":[],"
              + "\"syntax\":{\"type\":\"future-syntax\"},\"addedLater\":1}]");
      stub.setResponse(
          PREFIX + "/scheme/grammar",
          "{\"caseSensitive\":true,\"stripWhitespace\":false,\"addedLater\":1}");
      FormatSyntaxType type = scheme(stub).formats().get(0).syntax().type();
      assertEquals("future-syntax", type.toWire());
      assertEquals(FormatSyntaxType.Value.UNKNOWN, type.value());
      assertEquals(true, scheme(stub).grammar().caseSensitive());
    }
  }

  @Test
  void aReadAnswerThatIsNotTheDeclaredShapeIsRefusedAndNeverKept() throws Exception {
    try (ClientStubServer stub = stub()) {
      ContextScheme scheme = scheme(stub);
      // A list read that gets an object, an object read that gets a list, and rows that are not
      // objects are all malformed responses, reported as one exception type.
      stub.setResponse(PREFIX + "/scheme/categories", "{\"categories\":[]}");
      stub.setResponse(PREFIX + "/scheme", "[]");
      stub.setResponse(PREFIX + "/scheme/scopes", "[1,2]");
      stub.setResponse(PREFIX + "/scheme/grammar", "{\"caseSensitive\":true}");
      assertThrows(MalformedResponseException.class, scheme::categories);
      assertThrows(MalformedResponseException.class, scheme::summary);
      assertThrows(MalformedResponseException.class, scheme::scopes);
      MalformedResponseException missing =
          assertThrows(MalformedResponseException.class, scheme::grammar);
      assertTrue(missing.path().startsWith("$."), missing.path());

      // The next call asks the server again and sees the corrected answer.
      stub.setResponse(PREFIX + "/scheme/categories", CATEGORIES);
      assertEquals(2, scheme.categories().size());
    }
  }

  @Test
  void aRefusalIsRaisedWithItsStatusAndNotRetried() throws Exception {
    try (ClientStubServer stub = stub()) {
      stub.setStatus(PREFIX + "/scheme/formats", 404);
      stub.setResponse(
          PREFIX + "/scheme/formats", "{\"error\":\"scheme_not_found\",\"code\":\"NOT_FOUND\"}");
      EzClassifyClientException error =
          assertThrows(EzClassifyClientException.class, () -> scheme(stub).formats("nope"));
      assertEquals(404, error.statusCode());
      assertEquals("NOT_FOUND", error.code());
      assertEquals(1, stub.requestCount());
    }
  }
}
