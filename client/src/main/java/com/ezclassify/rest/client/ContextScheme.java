package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.CategoryView;
import com.ezclassify.rest.contract.CheckCoverageResult;
import com.ezclassify.rest.contract.ConceptDescription;
import com.ezclassify.rest.contract.ConceptNoticeType;
import com.ezclassify.rest.contract.ConceptView;
import com.ezclassify.rest.contract.ContextSchemeAggregateResult;
import com.ezclassify.rest.contract.ContextSchemeConceptDescriptionResult;
import com.ezclassify.rest.contract.ContextSchemeCoverageResult;
import com.ezclassify.rest.contract.ContextSchemeDiffResult;
import com.ezclassify.rest.contract.ContextSchemeExplainResult;
import com.ezclassify.rest.contract.ContextSchemeFilterConceptsResult;
import com.ezclassify.rest.contract.ContextSchemeNormalizeResult;
import com.ezclassify.rest.contract.ContextSchemeNoticesResult;
import com.ezclassify.rest.contract.ContextSchemeProcessResult;
import com.ezclassify.rest.contract.ContextSchemeRenderResult;
import com.ezclassify.rest.contract.ContextSchemeScanResult;
import com.ezclassify.rest.contract.ContextSchemeSuggestForCategoriesResult;
import com.ezclassify.rest.contract.ContextSchemeSuggestResult;
import com.ezclassify.rest.contract.ContextSchemeValidateResult;
import com.ezclassify.rest.contract.ContextSchemeViewResult;
import com.ezclassify.rest.contract.DiffResult;
import com.ezclassify.rest.contract.FilterResult;
import com.ezclassify.rest.contract.FormatView;
import com.ezclassify.rest.contract.LabelPresetView;
import com.ezclassify.rest.contract.NormalizeResponse;
import com.ezclassify.rest.contract.Notice;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.ProcessingContext;
import com.ezclassify.rest.contract.RenderPreservation;
import com.ezclassify.rest.contract.RenderResponse;
import com.ezclassify.rest.contract.ScanResponseBody;
import com.ezclassify.rest.contract.SchemeGrammar;
import com.ezclassify.rest.contract.SchemeScope;
import com.ezclassify.rest.contract.SchemeSummary;
import com.ezclassify.rest.contract.SchemeViewResponse;
import com.ezclassify.rest.contract.SuggestResult;
import com.ezclassify.rest.contract.ValidateResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheme-domain operations bound to an {@link EzClassifyContext}. Every call routes through {@code
 * /v1/ctx/{ctxId}/{version}/scheme/*}; the path selects the composed scheme, so request bodies
 * carry payload only.
 *
 * <h2 id="reading-and-writing">Reading a marking and writing one</h2>
 *
 * <p>Two operations answer about one marking, and they are split by who is asking. {@link
 * #validate(String)} is the reader's call: code that displays a marking, gates on it, stores it, or
 * hands its facts to an access check calls it. {@link #process(String)} is the author's call: it
 * answers everything validate does, settled the same way, plus what someone writing the marking
 * needs, which is the corrections to the text, the fix paths the server has proven, the outputs the
 * request names and the picker options it asks for. On both, {@code valid} means the marking stands
 * once the server has settled it and {@code inputWasValid} means it was right as written. {@link
 * #explain(String)} takes what process takes and returns the step-by-step trace instead.
 *
 * <h2 id="resources">Reading the scheme itself</h2>
 *
 * <p>{@link #summary()}, {@link #concepts()}, {@link #categories()}, {@link #formats()}, {@link
 * #grammar()}, {@link #scopes()} and {@link #labelPresets()} read what the composed scheme declares
 * rather than operate on a marking. Each answers the bare resource, with no result envelope, and
 * each reaches the server on every call; the response cache holds operations, not these reads.
 * Every one but {@code labelPresets} also takes the context-local key of another scheme the Context
 * holds, which is how a crosswalk's source and target are read. Presets belong to the primary
 * scheme, so {@code labelPresets} has no selector and the server refuses one.
 *
 * <h2 id="formats">Formats and outputs</h2>
 *
 * <p>An {@code inputFormat} names what the input is read as; {@code null} lets recognition work it
 * out. For a scheme that declares surfaces it may name a view, by view id or by {@code
 * surface/view} address, or one of that view's forms. Either way {@code recognition.inputFormat}
 * reports the view the text was read as, never the form it happened to be read in.
 *
 * <p>An {@code outputFormat} names the output a result is written in. It selects output only and
 * never narrows what may be read. A scheme without surfaces names its outputs by format id and
 * writes its default format when none is named. A scheme with surfaces names them by {@code
 * surface/view} address, and when none is named writes each surface in its default for the
 * marking's role: the request's context role when it gives one, otherwise the role recognition
 * decides and reports in {@code recognition.role}. A part and a whole can therefore come back in
 * different views.
 *
 * <p>A {@code surface} names which of the scheme's scopes the input is, for a fragment such as a
 * portion; {@code null} evaluates it as a whole marking.
 */
public final class ContextScheme {

  private final EzClassifyContext ctx;

  ContextScheme(EzClassifyContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Process a marking for someone who is writing it: the author's call.
   *
   * <p>The answer is everything {@link #validate(String)} answers, settled the same way, plus the
   * corrections that turn the text into canonical text, what was read before repair, and the fix
   * paths the server has proven. Code that only reads a marking calls {@link #validate(String)};
   * the trace of how the server got its answer is {@link #explain(String)}.
   *
   * @param input the marking text, as typed
   * @return the settled facts, {@code valid} and {@code inputWasValid}, the errors, corrections and
   *     proven fix paths, and the marking written back in {@code rendered}
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeProcessResult process(String input) {
    return process(input, ProcessOptions.defaults());
  }

  /**
   * Process a marking with every option the operation takes. Each option is sent under its wire
   * name only when set; {@code null} leaves it off the request.
   *
   * <p>A composer sends what was typed in {@code input} and the facts already chosen in {@code
   * assertions}, often on every keystroke, and the server settles the two together as one marking.
   * Either may be empty: an empty {@code input} with assertions processes the chosen facts alone.
   * Naming the outputs a host binds its fields to in {@code outputs}, and the categories whose
   * pickers it shows in {@code suggestFor}, gets the reading, every rendering and every picker's
   * options from one call. Nothing beyond what is named is written: {@code renders} is empty unless
   * outputs are named, and {@code suggestionsFor} is absent unless categories are.
   *
   * @param input the marking text, as typed; empty when {@code assertions} carries the marking
   * @param options immutable operation choices
   * @return the settled facts, {@code valid} and {@code inputWasValid}, the errors, corrections and
   *     proven fix paths, the marking written back in {@code rendered}, a rendering for each output
   *     named, and the options for each category named
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeProcessResult process(String input, ProcessOptions options) {
    Args.required(options, "options");
    String inputFormat = options.inputFormat();
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    List<Assertion> assertions = options.assertions();
    ProcessingContext context = options.context();
    List<String> parts = options.parts();
    List<String> outputs = options.outputs();
    List<String> suggestFor = options.suggestFor();

    Args.required(input, "input");
    JsonObject body = new JsonObject();
    Json.put(body, "input", input);
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    if (assertions != null) Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    if (context != null) Json.put(body, "context", WireJson.toTree(context));
    if (parts != null) Json.put(body, "parts", Json.stringArray(parts));
    if (outputs != null) Json.put(body, "outputs", Json.stringArray(outputs));
    if (suggestFor != null) Json.put(body, "suggestFor", Json.stringArray(suggestFor));
    return ContextJson.project(
        ctx.postResult(
            "scheme/process", body, value -> WireJson.fromTree(value, ProcessResult.class)),
        ContextSchemeProcessResult::new);
  }

  /**
   * Judge a marking for someone who is reading it: the reader's call.
   *
   * <p>Code that displays a marking, gates on it, stores it, or hands its facts to an access check
   * or a rollup calls this. The server settles the input exactly as {@link #process(String)} does
   * and then judges it, so {@code valid} and {@code inputWasValid} mean the same here as on every
   * other operation. Fix paths and corrections to the text are an author's concern and come from
   * {@link #process(String)}.
   *
   * @param input the marking text
   * @return the settled facts, the verdicts, errors and warnings, the changes settling made, the
   *     marking written back with its assessment, how recognition read it, the notices and the
   *     display color
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeValidateResult validate(String input) {
    return validate(input, ValidateOptions.defaults());
  }

  /**
   * Judge a marking given as text, with every option the operation takes. Each option is sent under
   * its wire name only when set; {@code null} leaves it off the request.
   *
   * @param input the marking text
   * @param options immutable operation choices
   * @return the settled facts, the verdicts, errors and warnings, the changes settling made, the
   *     marking written back with its assessment, how recognition read it, the notices and the
   *     display color
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeValidateResult validate(String input, ValidateOptions options) {
    Args.required(options, "options");
    String inputFormat = options.inputFormat();
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    ProcessingContext context = options.context();
    List<String> parts = options.parts();

    Args.required(input, "input");
    JsonObject body = new JsonObject();
    Json.put(body, "input", input);
    option(body, "inputFormat", inputFormat);
    return validate(body, outputFormat, surface, context, parts);
  }

  /**
   * Judge a marking given as facts: the reader's call for a marking that a document or an author
   * states as facts rather than text.
   *
   * <p>Pass facts only as they were stated, before any engine settled them. The {@code assertions}
   * of an earlier result are settled already, and validating them can call a marking valid whose
   * text was not.
   *
   * @param assertions the marking as facts as stated
   * @return the settled facts, the verdicts, errors and warnings, the changes settling made, the
   *     marking written back with its assessment, the notices and the display color
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeValidateResult validate(List<Assertion> assertions) {
    return validate(assertions, FactsValidateOptions.defaults());
  }

  /**
   * Judge a marking given as facts, with every option the operation takes. Each option is sent
   * under its wire name only when set; {@code null} leaves it off the request. There is no input
   * format: facts carry no text to read.
   *
   * @param assertions the marking as facts as stated, never the settled assertions of an earlier
   *     result
   * @param options immutable operation choices
   * @return the settled facts, the verdicts, errors and warnings, the changes settling made, the
   *     marking written back with its assessment, the notices and the display color
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeValidateResult validate(
      List<Assertion> assertions, FactsValidateOptions options) {
    Args.required(options, "options");
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    ProcessingContext context = options.context();
    List<String> parts = options.parts();

    Args.requiredList(assertions, "assertions");
    // The route takes the marking in exactly one of `input` and `assertions` and refuses a body
    // with both, so facts go out with no `input` beside them.
    JsonObject body = new JsonObject();
    Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    return validate(body, outputFormat, surface, context, parts);
  }

  private ContextSchemeValidateResult validate(
      JsonObject body,
      String outputFormat,
      String surface,
      ProcessingContext context,
      List<String> parts) {
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    if (context != null) Json.put(body, "context", WireJson.toTree(context));
    if (parts != null) Json.put(body, "parts", Json.stringArray(parts));
    return ContextJson.project(
        ctx.postResult(
            "scheme/validate", body, value -> WireJson.fromTree(value, ValidateResult.class)),
        ContextSchemeValidateResult::new);
  }

  /**
   * Explain, step by step, how the server settles a marking.
   *
   * <p>Takes what {@link #process(String)} takes, apart from the outputs and picker categories that
   * only add to a process result, and returns the trace of that same processing: what was read,
   * repaired, inferred and found wrong, and what was written. Reach for it when someone wants to
   * know why a marking came out the way it did. No other operation returns the trace.
   *
   * @param input the marking text, as typed
   * @return the trace: each step, the repairs and inferences, the validation issues, the final
   *     assertions, and the marking as written
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeExplainResult explain(String input) {
    return explain(input, ExplainOptions.defaults());
  }

  /**
   * Explain how the server settles a marking, with every option the operation takes. Each option is
   * sent under its wire name only when set; {@code null} leaves it off the request.
   *
   * @param input the marking text, as typed; empty when {@code assertions} carries the marking
   * @param options immutable operation choices
   * @return the trace: each step, the repairs and inferences, the validation issues, the final
   *     assertions, and the marking as written
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeExplainResult explain(String input, ExplainOptions options) {
    Args.required(options, "options");
    String inputFormat = options.inputFormat();
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    List<Assertion> assertions = options.assertions();
    ProcessingContext context = options.context();
    List<String> parts = options.parts();

    Args.required(input, "input");
    JsonObject body = new JsonObject();
    Json.put(body, "input", input);
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    if (assertions != null) Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    if (context != null) Json.put(body, "context", WireJson.toTree(context));
    if (parts != null) Json.put(body, "parts", Json.stringArray(parts));
    return ContextJson.project(
        ctx.postResult(
            "scheme/explain", body, value -> ContextJson.explanationTrace(value.getAsJsonObject())),
        ContextSchemeExplainResult::new);
  }

  /**
   * Picker options for a partial marking.
   *
   * @param input the text typed so far; an empty string returns every selectable concept
   * @return the concepts a picker may offer, and any advisories raised while building them. How the
   *     marking reads and whether it stands are what {@link #validate(String)} and {@link
   *     #process(String)} answer
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeSuggestResult suggest(String input) {
    return suggest(input, SuggestOptions.defaults());
  }

  /**
   * Picker options with every option the operation takes. Each option is sent under its wire name
   * only when set; {@code null} leaves it off the request, and {@code false} is sent.
   *
   * @param input the text typed so far; an empty string returns every selectable concept
   * @param options immutable operation choices
   * @return the concepts a picker may offer, and any advisories raised while building them
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeSuggestResult suggest(String input, SuggestOptions options) {
    Args.required(options, "options");
    String inputFormat = options.inputFormat();
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    String filter = options.filter();
    Boolean includeGated = options.includeGated();

    Args.required(input, "input");
    JsonObject body = new JsonObject();
    Json.put(body, "input", input);
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    option(body, "filter", filter);
    if (includeGated != null) body.addProperty("includeGated", includeGated);
    return ContextJson.project(
        ctx.postResult("scheme/suggest", body, value -> suggestResult(value, "suggest")),
        ContextSchemeSuggestResult::new);
  }

  /**
   * Request picker alternatives for facts, with no text input beside them.
   *
   * @param assertions the complete committed facts
   * @return the available choices and advisories
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeSuggestResult suggest(List<Assertion> assertions) {
    return suggest(assertions, FactsSuggestOptions.defaults());
  }

  /**
   * Request picker alternatives for facts using immutable presentation choices.
   *
   * @param assertions the complete committed facts
   * @param options immutable choices with no text input format
   * @return the available choices and advisories
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeSuggestResult suggest(
      List<Assertion> assertions, FactsSuggestOptions options) {
    Args.requiredList(assertions, "assertions");
    Args.required(options, "options");
    JsonObject body = new JsonObject();
    Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    option(body, "outputFormat", options.outputFormat());
    option(body, "surface", options.surface());
    option(body, "filter", options.filter());
    if (options.includeGated() != null) body.addProperty("includeGated", options.includeGated());
    return ContextJson.project(
        ctx.postResult("scheme/suggest", body, value -> suggestResult(value, "suggest")),
        ContextSchemeSuggestResult::new);
  }

  /**
   * Picker options for several categories against one committed assertion set, in one round trip.
   *
   * <p>Each category is answered as if that category's own assertions were absent, so a picker
   * shows the alternatives to the current selection rather than only what is still addable beside
   * it. An author who is processing the marking anyway gets the same map from that call by naming
   * the categories in its {@code suggestFor}.
   *
   * @param assertions the caller's complete committed assertion set
   * @param categoryIds category ids to produce suggestions for
   * @return the options keyed by category id, one entry for each requested category
   * @throws IllegalArgumentException if a required argument is null
   * @throws EzClassifyClientException if the server rejects the call, or answers without a result
   *     for every requested category
   */
  public ContextSchemeSuggestForCategoriesResult suggestForCategories(
      List<Assertion> assertions, List<String> categoryIds) {
    return suggestForCategories(assertions, categoryIds, null, null);
  }

  /**
   * Picker options for several categories, choosing the output previews are written in and the
   * suggestion surface. Each option is sent under its wire name only when set. There is no input
   * format: the committed state is already facts, never text.
   *
   * @param assertions the caller's complete committed assertion set
   * @param categoryIds category ids to produce suggestions for
   * @param outputFormat the output suggestion previews are written in, or {@code null} for the
   *     default (see <a href="#formats">Formats and outputs</a>)
   * @param surface the suggestion surface, named with one of the scheme's scope ids, restricting
   *     suggestions to the concepts and categories that surface owns; {@code null} for the whole
   *     marking
   * @return the options keyed by category id, one entry for each requested category
   * @throws IllegalArgumentException if a required argument is null
   * @throws EzClassifyClientException if the server rejects the call, or answers without a result
   *     for every requested category
   */
  public ContextSchemeSuggestForCategoriesResult suggestForCategories(
      List<Assertion> assertions, List<String> categoryIds, String outputFormat, String surface) {
    Args.requiredList(assertions, "assertions");
    Args.requiredList(categoryIds, "categoryIds");
    JsonObject body = new JsonObject();
    Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    Json.put(body, "categoryIds", Json.stringArray(categoryIds));
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    // The answer is the map itself, so naming no category legitimately comes back as an empty
    // object; the check below, not the envelope, decides whether the answer is whole.
    return ContextJson.project(
        ctx.postResult(
            "scheme/suggest-for-categories",
            body,
            value -> suggestionsByCategory(value, categoryIds),
            EzClassifyContext.EmptyResult.ALLOWED),
        ContextSchemeSuggestForCategoriesResult::new);
  }

  /**
   * Check the suggest-for-categories answer and key it by category id, in the order the server sent
   * it. A caller looks a category up by an id it already holds, so a partial answer would surface
   * as a {@code null} far from the call that caused it. This runs inside the cached call, so a
   * malformed answer is never kept for the next caller.
   */
  private static Map<String, SuggestResult> suggestionsByCategory(
      JsonElement value, List<String> categoryIds) {
    if (!value.isJsonObject()) {
      throw new MalformedResponseException(
          "Context suggest-for-categories response is not a map of results keyed by category id");
    }
    Map<String, SuggestResult> byCategory = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
      if (!entry.getValue().isJsonObject()) {
        throw new MalformedResponseException(
            "Context suggest-for-categories response carries no result for category "
                + entry.getKey());
      }
      byCategory.put(entry.getKey(), suggestResult(entry.getValue(), "suggest-for-categories"));
    }
    List<String> missing =
        categoryIds.stream().filter(id -> !byCategory.containsKey(id)).distinct().toList();
    if (!missing.isEmpty()) {
      throw new MalformedResponseException(
          "Context suggest-for-categories response answered no result for: "
              + String.join(", ", missing));
    }
    return Collections.unmodifiableMap(byCategory);
  }

  /**
   * Decode one suggestion result. Suggest, each suggest-for-categories entry and each entry of a
   * process result's {@code suggestionsFor} all decode through {@link WireJson}, so one wire shape
   * has one reader.
   */
  private static SuggestResult suggestResult(JsonElement value, String operation) {
    if (!value.isJsonObject()) {
      throw new MalformedResponseException(
          "Context " + operation + " result is not a suggestion result object");
    }
    return WireJson.fromTree(value, SuggestResult.class);
  }

  /**
   * Aggregate multiple markings into a single rolled-up marking.
   *
   * @param inputs the part markings to combine
   * @return the rolled-up whole marking, with the aggregated assertions and rendering
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeAggregateResult aggregate(List<String> inputs) {
    return aggregate(inputs, AggregateOptions.defaults());
  }

  /**
   * Aggregate multiple markings with every option the operation takes. Each option is sent under
   * its wire name only when set.
   *
   * @param inputs the part markings to combine
   * @param options immutable operation choices
   * @return the rolled-up whole marking, with the aggregated assertions and rendering
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeAggregateResult aggregate(List<String> inputs, AggregateOptions options) {
    Args.required(options, "options");
    String inputFormat = options.inputFormat();
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    ProcessingContext context = options.context();

    Args.requiredList(inputs, "inputs");
    JsonObject body = new JsonObject();
    Json.put(body, "inputs", Json.stringArray(inputs));
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    if (context != null) Json.put(body, "context", WireJson.toTree(context));
    return ContextJson.project(
        ctx.postResult(
            "scheme/aggregate",
            body,
            value -> ContextJson.aggregateResult(value.getAsJsonObject())),
        ContextSchemeAggregateResult::new);
  }

  /**
   * Aggregate already-parsed assertion sets into a single rolled-up marking.
   *
   * <p>The counterpart to {@link #aggregate(List)} for callers that already hold parsed assertions.
   * Each inner list is one marking's assertions; the request carries them in the {@code
   * assertionSets} body field (mutually exclusive with {@code inputs}), skipping the server's
   * string-parse stage while the rest of the aggregate pipeline runs identically. Transports
   * marking state losslessly: scope-only assertions and capture values survive the round trip.
   *
   * @param assertionSets the assertion sets to combine, one inner list per marking
   * @return the rolled-up whole marking, with the aggregated assertions and rendering
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeAggregateResult aggregateAssertions(List<List<Assertion>> assertionSets) {
    return aggregateAssertions(assertionSets, FactsAggregateOptions.defaults());
  }

  /**
   * Aggregate already-parsed assertion sets with every option the operation takes. Each option is
   * sent under its wire name only when set. There is no input format: nothing is parsed.
   *
   * @param assertionSets the assertion sets to combine, one inner list per marking
   * @param options immutable operation choices
   * @return the rolled-up whole marking, with the aggregated assertions and rendering
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeAggregateResult aggregateAssertions(
      List<List<Assertion>> assertionSets, FactsAggregateOptions options) {
    Args.required(options, "options");
    String outputFormat = options.outputFormat();
    String surface = options.surface();
    ProcessingContext context = options.context();

    Args.requiredList(assertionSets, "assertionSets");
    JsonObject body = new JsonObject();
    Json.put(body, "assertionSets", ContextJson.assertionSetArray(assertionSets));
    option(body, "outputFormat", outputFormat);
    option(body, "surface", surface);
    if (context != null) Json.put(body, "context", WireJson.toTree(context));
    return ContextJson.project(
        ctx.postResult(
            "scheme/aggregate",
            body,
            value -> ContextJson.aggregateResult(value.getAsJsonObject())),
        ContextSchemeAggregateResult::new);
  }

  /**
   * Scan free text for markings recognized by the context's scheme.
   *
   * @param text the free text to scan
   * @return the markings found, each with its position in the source text and what validate answers
   *     for it, read in the role it was found in, and how far the scan got. A scan reads a document
   *     and does not author it, so a hit carries no fix paths; process one region to correct it. An
   *     empty result list is what a clean document and an abandoned scan both look like, so callers
   *     that act on the answer read the limits.
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeScanResult scan(String text) {
    return scan(text, ScanOptions.defaults());
  }

  /**
   * Scan free text for markings with every option the operation takes. Each option is sent under
   * its wire name only when set; {@code null} leaves it off the request.
   *
   * @param text the free text to scan
   * @param options immutable operation choices
   * @return the markings found, each with what validate answers for it, and how far the scan got
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeScanResult scan(String text, ScanOptions options) {
    Args.required(options, "options");
    String inputFormat = options.inputFormat();
    String outputFormat = options.outputFormat();

    Args.required(text, "text");
    JsonObject body = new JsonObject();
    Json.put(body, "text", text);
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    return ContextJson.project(
        ctx.postResult(
            "scheme/scan", body, value -> WireJson.fromTree(value, ScanResponseBody.class)),
        ContextSchemeScanResult::new);
  }

  /**
   * Render assertions into a marking string in the scheme's default format.
   *
   * @param assertions the assertions to render
   * @return the rendered marking string in the scheme's default format
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeRenderResult render(List<Assertion> assertions) {
    return render(assertions, RenderOptions.defaults());
  }

  /**
   * Render assertions in a named output with an explicit preservation requirement.
   *
   * @param assertions the assertions to render
   * @param options immutable operation choices
   * @return the rendered marking and its preservation assessment
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeRenderResult render(List<Assertion> assertions, RenderOptions options) {
    Args.required(options, "options");
    String outputFormat = options.outputFormat();
    RenderPreservation preservation = options.preservation();

    Args.requiredList(assertions, "assertions");
    JsonObject body = new JsonObject();
    Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    option(body, "outputFormat", outputFormat);
    if (preservation != null) Json.put(body, "preservation", preservation.toWire());
    return ContextJson.project(
        ctx.postResult(
            "scheme/render", body, value -> WireJson.fromTree(value, RenderResponse.class)),
        ContextSchemeRenderResult::new);
  }

  /**
   * Return the canonical normalized form of a marking.
   *
   * @param input the raw marking text to normalize
   * @return the canonical rendering of the marking
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeNormalizeResult normalize(String input) {
    return normalize(input, null, null);
  }

  /**
   * Return the canonical normalized form of a marking, choosing what the input is read as and the
   * output the canonical form is written in. Each option is sent under its wire name only when set.
   *
   * @param input the raw marking text to normalize
   * @param inputFormat what {@code input} is read as, or {@code null} to let recognition work it
   *     out (see <a href="#formats">Formats and outputs</a>)
   * @param outputFormat the output the canonical form is written in, or {@code null} for the
   *     default (see <a href="#formats">Formats and outputs</a>)
   * @return the canonical rendering of the marking
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeNormalizeResult normalize(
      String input, String inputFormat, String outputFormat) {
    Args.required(input, "input");
    JsonObject body = new JsonObject();
    Json.put(body, "input", input);
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    return ContextJson.project(
        ctx.postResult(
            "scheme/normalize", body, value -> WireJson.fromTree(value, NormalizeResponse.class)),
        ContextSchemeNormalizeResult::new);
  }

  /**
   * Report whether a set of child markings is covered by a parent marking.
   *
   * @param children the part (child) markings to test for coverage
   * @param parent the whole (parent) marking they should be covered by
   * @return the coverage report describing whether and how the parent covers the children
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeCoverageResult checkCoverage(List<String> children, String parent) {
    return checkCoverage(children, parent, null, null);
  }

  /**
   * Report whether child markings are covered by a parent marking, choosing what the markings are
   * read as and the output markings in the report are written in. Each option is sent under its
   * wire name only when set.
   *
   * @param children the part (child) markings to test for coverage
   * @param parent the whole (parent) marking they should be covered by
   * @param inputFormat what the parent and child markings are read as, or {@code null} to let
   *     recognition work it out (see <a href="#formats">Formats and outputs</a>)
   * @param outputFormat the output markings in the report are written in, or {@code null} for the
   *     default (see <a href="#formats">Formats and outputs</a>)
   * @return the coverage report describing whether and how the parent covers the children
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeCoverageResult checkCoverage(
      List<String> children, String parent, String inputFormat, String outputFormat) {
    Args.requiredList(children, "children");
    Args.required(parent, "parent");
    JsonObject body = new JsonObject();
    Json.put(body, "children", Json.stringArray(children));
    Json.put(body, "parent", parent);
    option(body, "inputFormat", inputFormat);
    option(body, "outputFormat", outputFormat);
    return ContextJson.project(
        ctx.postResult(
            "scheme/check-coverage",
            body,
            value -> WireJson.fromTree(value, CheckCoverageResult.class)),
        ContextSchemeCoverageResult::new);
  }

  /**
   * Describe a concept's definition, guidance, and applicable rules.
   *
   * @param conceptId the id of the concept to describe
   * @param categoryId the id of the category the concept belongs to
   * @return the concept description, with its display name, definition, guidance, and applicable
   *     rules
   * @throws IllegalArgumentException if a required argument is null or empty
   */
  public ContextSchemeConceptDescriptionResult describeConcept(
      String conceptId, String categoryId) {
    return describeConcept(conceptId, categoryId, null);
  }

  /**
   * Describe a concept. {@code view} optionally selects the concept property used for the display
   * name; pass {@code null} for the default.
   *
   * @param conceptId the id of the concept to describe
   * @param categoryId the id of the category the concept belongs to
   * @param view the view that selects the display name, or {@code null} for the default
   * @return the concept description, with its display name, definition, guidance, and applicable
   *     rules
   * @throws IllegalArgumentException if {@code conceptId} or {@code categoryId} is empty
   */
  public ContextSchemeConceptDescriptionResult describeConcept(
      String conceptId, String categoryId, String view) {
    StringBuilder q =
        new StringBuilder("scheme/describe-concept?conceptId=")
            .append(requiredParam(conceptId, "conceptId"))
            .append("&categoryId=")
            .append(requiredParam(categoryId, "categoryId"));
    if (view != null && !view.isEmpty()) q.append("&view=").append(enc(view));
    return ContextJson.project(
        ctx.getResult(q.toString(), value -> conceptDescription(value.getAsJsonObject())),
        ContextSchemeConceptDescriptionResult::new);
  }

  private static ConceptDescription conceptDescription(JsonObject o) {
    return new ConceptDescription(
        Json.optString(o, "conceptId"),
        Json.optString(o, "categoryId"),
        Json.optString(o, "name"),
        Json.isNull(o, "definition") ? null : Json.optString(o, "definition"),
        Json.isNull(o, "guidance") ? null : Json.optString(o, "guidance"),
        o.has("rules") ? ContextJson.stringList(Json.optArray(o, "rules")) : null,
        ContextJson.stringList(Json.optArray(o, "lines")));
  }

  /**
   * Diff two marking strings into added / removed / common assertions.
   *
   * @param a the baseline marking
   * @param b the marking to compare against the baseline
   * @return the diff, grouping assertions into added, removed, and common
   */
  public ContextSchemeDiffResult diff(String a, String b) {
    return diff(a, b, null);
  }

  /**
   * Diff two marking strings, naming what both are read as. There is no output format: the result
   * is assertions, not a rendered string.
   *
   * @param a the baseline marking
   * @param b the marking to compare against the baseline
   * @param inputFormat what both markings are read as, or {@code null} to let recognition work it
   *     out (see <a href="#formats">Formats and outputs</a>)
   * @return the diff, grouping assertions into added, removed, and common
   */
  public ContextSchemeDiffResult diff(String a, String b, String inputFormat) {
    JsonObject body = new JsonObject();
    Json.put(body, "a", a);
    Json.put(body, "b", b);
    option(body, "inputFormat", inputFormat);
    return ContextJson.project(
        ctx.postResult("scheme/diff", body, value -> diffResult(value.getAsJsonObject())),
        ContextSchemeDiffResult::new);
  }

  private static DiffResult diffResult(JsonObject o) {
    return new DiffResult(
        ContextJson.assertions(Json.optArray(o, "added")),
        ContextJson.assertions(Json.optArray(o, "common")),
        ContextJson.assertions(Json.optArray(o, "removed")));
  }

  /**
   * Substring-filter the context's concepts.
   *
   * @param query the substring to filter concept names by
   * @return the matching concept ids and the visible/total counts per category
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeFilterConceptsResult filterConcepts(String query) {
    Args.required(query, "query");
    JsonObject body = new JsonObject();
    Json.put(body, "query", query);
    return ContextJson.project(
        ctx.postResult(
            "scheme/filter-concepts", body, value -> filterResult(value.getAsJsonObject())),
        ContextSchemeFilterConceptsResult::new);
  }

  /**
   * Collect the concept notices for a set of assertions.
   *
   * <p>Call this when you hold assertions that did not come from a result of this client, for
   * example a set assembled from a picker. Process, validate, aggregate, and every other rich
   * result already carry their own notices in the {@code notices} field, so reading the notices for
   * a marking you just processed takes no second round trip.
   *
   * @param assertions the assertions to gather notices for
   * @return the notices that apply to the supplied assertions
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextSchemeNoticesResult notices(List<Assertion> assertions) {
    Args.requiredList(assertions, "assertions");
    JsonObject body = new JsonObject();
    Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    return ContextJson.project(
        ctx.postResult("scheme/notices", body, value -> notices(value.getAsJsonArray())),
        ContextSchemeNoticesResult::new);
  }

  private static List<Notice> notices(JsonArray arr) {
    List<Notice> out = new java.util.ArrayList<>(arr.size());
    for (int i = 0; i < arr.size(); i++) {
      JsonObject n = arr.get(i).getAsJsonObject();
      out.add(
          new Notice(
              Json.optString(n, "conceptId"),
              Json.optString(n, "conceptName"),
              ConceptNoticeType.fromString(Json.requiredString(n, "type")),
              Json.optString(n, "text"),
              Json.requiredBoolean(n, "required"),
              ContextJson.stringMap(Json.optObject(n, "views"))));
    }
    return List.copyOf(out);
  }

  /**
   * Build a render-ready structural view of the context's composed scheme.
   *
   * @return the structural view, with the scheme's concepts grouped by category
   */
  public ContextSchemeViewResult view() {
    return ContextJson.project(
        ctx.postResult(
            "scheme/view",
            new JsonObject(),
            value -> WireJson.fromTree(value, SchemeViewResponse.class)),
        ContextSchemeViewResult::new);
  }

  /**
   * Read the metadata of the context's primary composed scheme.
   *
   * <p>The summary is the scheme's identity and presentation: id, version, name, description, the
   * default scope and render formats, the display vocabulary and color rules. It answers the bare
   * resource with no result envelope, and it reaches the server on every call.
   *
   * @return the scheme summary
   * @throws EzClassifyClientException if the server rejects the request, including a 429 that
   *     carries {@link EzClassifyClientException#retryAfter()}, or answers a malformed body
   */
  public SchemeSummary summary() {
    return summary(null);
  }

  /**
   * Read the metadata of a scheme held by this Context.
   *
   * @param scheme the context-local scheme key or artifact id, or {@code null} for the primary
   *     scheme
   * @return the scheme summary
   * @throws IllegalArgumentException if {@code scheme} is blank
   * @throws EzClassifyClientException if the server rejects the request, including a 404 for a key
   *     the Context does not hold and a 429 that carries {@link
   *     EzClassifyClientException#retryAfter()}, or answers a malformed body
   */
  public SchemeSummary summary(String scheme) {
    return ctx.getResource("scheme" + schemeQuery(scheme), SchemeSummary.class);
  }

  /**
   * List every concept of the context's primary composed scheme.
   *
   * @return the concepts as concept views: id, name, views, categories, parents, position, rank and
   *     display metadata
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<ConceptView> concepts() {
    return concepts(null);
  }

  /**
   * List every concept of a scheme held by this Context.
   *
   * @param scheme the context-local scheme key or artifact id, or {@code null} for the primary
   *     scheme
   * @return the concepts as concept views: id, name, views, categories, parents, position, rank and
   *     display metadata
   * @throws IllegalArgumentException if {@code scheme} is blank
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<ConceptView> concepts(String scheme) {
    return ctx.getResourceList("scheme/concepts" + schemeQuery(scheme), ConceptView.class);
  }

  /**
   * List every category of the context's primary composed scheme, in declared order.
   *
   * @return the categories as category views: label, scopes, rollup, default concept and concept
   *     sort order
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<CategoryView> categories() {
    return categories(null);
  }

  /**
   * List every category of a scheme held by this Context, in declared order.
   *
   * @param scheme the context-local scheme key or artifact id, or {@code null} for the primary
   *     scheme
   * @return the categories as category views: label, scopes, rollup, default concept and concept
   *     sort order
   * @throws IllegalArgumentException if {@code scheme} is blank
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<CategoryView> categories(String scheme) {
    return ctx.getResourceList("scheme/categories" + schemeQuery(scheme), CategoryView.class);
  }

  /**
   * List the formats the context's primary composed scheme writes.
   *
   * @return one format view per format: its id, the surface it writes, its resolved layout and how
   *     its text is framed. The field trees stay server-side.
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<FormatView> formats() {
    return formats(null);
  }

  /**
   * List the formats a scheme held by this Context writes.
   *
   * @param scheme the context-local scheme key or artifact id, or {@code null} for the primary
   *     scheme
   * @return one format view per format: its id, the surface it writes, its resolved layout and how
   *     its text is framed. The field trees stay server-side.
   * @throws IllegalArgumentException if {@code scheme} is blank
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<FormatView> formats(String scheme) {
    return ctx.getResourceList("scheme/formats" + schemeQuery(scheme), FormatView.class);
  }

  /**
   * Read the parse grammar of the context's primary composed scheme.
   *
   * @return whether the vocabulary is matched case-sensitively and whether surrounding whitespace
   *     is ignored
   * @throws EzClassifyClientException if the server rejects the request or answers a malformed body
   */
  public SchemeGrammar grammar() {
    return grammar(null);
  }

  /**
   * Read the parse grammar of a scheme held by this Context.
   *
   * @param scheme the context-local scheme key or artifact id, or {@code null} for the primary
   *     scheme
   * @return whether the vocabulary is matched case-sensitively and whether surrounding whitespace
   *     is ignored
   * @throws IllegalArgumentException if {@code scheme} is blank
   * @throws EzClassifyClientException if the server rejects the request or answers a malformed body
   */
  public SchemeGrammar grammar(String scheme) {
    return ctx.getResource("scheme/grammar" + schemeQuery(scheme), SchemeGrammar.class);
  }

  /**
   * List the scopes the context's primary composed scheme declares, in declaration order.
   *
   * @return the scope definitions in the order a selector offers them; empty, never {@code null},
   *     when the scheme declares none
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<SchemeScope> scopes() {
    return scopes(null);
  }

  /**
   * List the scopes a scheme held by this Context declares, in declaration order.
   *
   * @param scheme the context-local scheme key or artifact id, or {@code null} for the primary
   *     scheme
   * @return the scope definitions in the order a selector offers them; empty, never {@code null},
   *     when the scheme declares none
   * @throws IllegalArgumentException if {@code scheme} is blank
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<SchemeScope> scopes(String scheme) {
    return ctx.getResourceList("scheme/scopes" + schemeQuery(scheme), SchemeScope.class);
  }

  /**
   * List the label presets of the context's primary scheme.
   *
   * <p>A preset is listed by name and scope only: the marking it stands for stays server-side, and
   * {@link #applyPreset(String)} is the only way to expand one. Presets belong to the primary
   * scheme, the one {@code applyPreset} expands them in, so there is no scheme selector and the
   * server refuses one.
   *
   * @return one preset view per preset: id, label, description and scope; empty, never {@code
   *     null}, when the scheme declares none
   * @throws EzClassifyClientException if the server rejects the request or returns a non-array
   *     response
   */
  public List<LabelPresetView> labelPresets() {
    return ctx.getResourceList("scheme/label-presets", LabelPresetView.class);
  }

  /**
   * Expand a label preset through the context's primary scheme, written in the scheme's default
   * output.
   *
   * @param presetId the id of the preset to expand
   * @return what {@link #validate(String)} answers for the marking the preset stands for
   * @throws IllegalArgumentException if {@code presetId} is null or blank
   * @throws EzClassifyClientException if the server rejects the call, including a 404 when the
   *     scheme declares no preset by that id
   */
  public ContextSchemeValidateResult applyPreset(String presetId) {
    return applyPreset(presetId, null);
  }

  /**
   * Expand a label preset, choosing the output it is written in.
   *
   * <p>A preset's marking string stays server-side, so this is the only way to order one. A preset
   * is a stored marking, so there is nothing to correct and no fix path to offer: the answer is
   * what validate answers for the marking it stands for.
   *
   * @param presetId the id of the preset to expand
   * @param outputFormat the output to write: a format id, or for a scheme that declares surfaces,
   *     an output as that scheme defines it, such as a {@code surface/view} address; {@code null}
   *     for the scheme's default (see <a href="#formats">Formats and outputs</a>)
   * @return what {@link #validate(String)} answers for the marking the preset stands for
   * @throws IllegalArgumentException if {@code presetId} is null or blank
   * @throws EzClassifyClientException if the server rejects the call, including a 404 when the
   *     scheme declares no preset by that id
   */
  public ContextSchemeValidateResult applyPreset(String presetId, String outputFormat) {
    Args.requiredId(presetId, "presetId");
    JsonObject body = new JsonObject();
    Json.put(body, "presetId", presetId);
    option(body, "outputFormat", outputFormat);
    return ContextJson.project(
        ctx.postResult(
            "scheme/apply-preset", body, value -> WireJson.fromTree(value, ValidateResult.class)),
        ContextSchemeValidateResult::new);
  }

  // --- parsing helpers shared with guide view -----------------------------

  static FilterResult filterResult(JsonObject o) {
    // Insertion-ordered like every other wire-map parse in the client (Json.toMap,
    // ContextJson.stringMap), so category counts iterate in wire order.
    java.util.Map<String, FilterResult.Count> counts = new java.util.LinkedHashMap<>();
    JsonObject cc = Json.optObject(o, "categoryCounts");
    if (cc != null) {
      for (String key : cc.keySet()) {
        JsonObject c = cc.get(key).getAsJsonObject();
        counts.put(key, new FilterResult.Count(Json.optInt(c, "visible"), Json.optInt(c, "total")));
      }
    }
    JsonObject total = Json.optObject(o, "total");
    FilterResult.Count totalCount =
        total == null
            ? new FilterResult.Count(0, 0)
            : new FilterResult.Count(Json.optInt(total, "visible"), Json.optInt(total, "total"));
    return new FilterResult(
        ContextJson.stringList(Json.optArray(o, "visibleIds")), counts, totalCount);
  }

  /**
   * Set an optional body field under its wire name when the caller supplied a value. An empty
   * string counts as unset, so it never reaches the server as an unknown format or scope id.
   */
  private static void option(JsonObject body, String wireName, String value) {
    if (value != null && !value.isEmpty()) Json.put(body, wireName, value);
  }

  /**
   * The query a scheme resource read sends: nothing for the primary scheme, otherwise the one
   * parameter these operations declare.
   */
  private static String schemeQuery(String scheme) {
    return scheme == null ? "" : "?scheme=" + requiredParam(scheme, "scheme");
  }

  /** Encode a required query-string identifier, refusing an empty one at the boundary. */
  private static String requiredParam(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
    return enc(value);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
