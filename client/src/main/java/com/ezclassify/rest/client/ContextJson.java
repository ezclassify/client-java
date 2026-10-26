package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.AggregateResult;
import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.AssessmentLimitation;
import com.ezclassify.rest.contract.Change;
import com.ezclassify.rest.contract.CollectedNotice;
import com.ezclassify.rest.contract.ContextOperationResult;
import com.ezclassify.rest.contract.ContextResolution;
import com.ezclassify.rest.contract.DocumentResponseProjection;
import com.ezclassify.rest.contract.ExplanationTrace;
import com.ezclassify.rest.contract.Fix;
import com.ezclassify.rest.contract.GuideBreakdownEntry;
import com.ezclassify.rest.contract.ValidationError;
import com.ezclassify.rest.contract.Warning;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON helpers for the HTTP client surface: request-body builders and parsers for the value
 * objects that recur across operations (assertions, validation errors, warnings, fixes, changes,
 * notices, explanation traces).
 *
 * <p>The Context-bound surface delegates typed decoding to {@link WireJson}, so every operation
 * yields the same deliberate Java projection of its wire payload.
 */
final class ContextJson {

  private ContextJson() {}

  /**
   * The canonical constructor of a Context envelope record. Every Context operation returns its own
   * nominal envelope type with the same four components, so the projection from {@link
   * ContextOperationResult} is one shape applied many times rather than one method per operation.
   *
   * @param <T> the operation-specific result type
   * @param <R> the envelope record the operation returns
   */
  @FunctionalInterface
  interface Envelope<T, R> {
    /**
     * Build the envelope record.
     *
     * @param resolution the resolved Context composition
     * @param result the operation result
     * @param operationLimitations limitations the server reported for this operation
     * @param responseProjection the projection the server applied, or {@code null}
     * @return the operation's envelope record
     */
    R create(
        ContextResolution resolution,
        T result,
        List<AssessmentLimitation> operationLimitations,
        DocumentResponseProjection responseProjection);
  }

  /**
   * Project a parsed Context operation result into the operation's own envelope record.
   *
   * @param value the parsed envelope
   * @param envelope the target record's canonical constructor
   * @param <T> the operation-specific result type
   * @param <R> the envelope record the operation returns
   * @return the operation's envelope record
   */
  static <T, R> R project(ContextOperationResult<T> value, Envelope<T, R> envelope) {
    return envelope.create(
        value.resolution(),
        value.result(),
        value.operationLimitations(),
        value.responseProjection());
  }

  /** Parse a JSON array of strings into an insertion-ordered list; empty when absent. */
  static List<String> stringList(JsonArray arr) {
    if (arr == null) return List.of();
    List<String> out = new ArrayList<>(arr.size());
    for (int i = 0; i < arr.size(); i++) out.add(Json.optString(arr, i));
    return List.copyOf(out);
  }

  /**
   * Parse a JSON object of string values into an insertion-ordered map, {@code null} when absent.
   */
  static Map<String, String> stringMap(JsonObject o) {
    if (o == null) return null;
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (String key : o.keySet()) out.put(key, Json.isNull(o, key) ? null : Json.optString(o, key));
    return out;
  }

  /**
   * Project assertions to their wire shape for a request. Every populated field travels, including
   * contract enum tokens this SDK does not know: assertion arrays are the engine's state transport,
   * so an assertion read from one response must reach the next request unchanged. Dropping {@code
   * expiresAt}, for example, would strip an expiry from a data assertion on access check. Absent
   * fields are omitted rather than sent as {@code null}.
   */
  static JsonArray assertionArray(List<Assertion> assertions) {
    JsonArray arr = new JsonArray();
    if (assertions == null) return arr;
    for (Assertion a : assertions) arr.add(WireJson.toTree(a));
    return arr;
  }

  /**
   * Project a list of assertion sets to the wire shape the aggregate endpoint expects: a JSON array
   * of assertion arrays, one inner array per marking. Each inner array uses the same camelCase
   * projection as {@link #assertionArray(List)}, so scope-only assertions and capture values travel
   * unchanged.
   */
  static JsonArray assertionSetArray(List<List<Assertion>> assertionSets) {
    JsonArray arr = new JsonArray();
    if (assertionSets == null) return arr;
    for (List<Assertion> set : assertionSets) {
      arr.add(assertionArray(set));
    }
    return arr;
  }

  static List<Assertion> assertions(JsonArray arr) {
    return WireJson.projectedListFromTree(arr, Assertion.class);
  }

  static List<ValidationError> validationErrors(JsonArray arr) {
    return WireJson.projectedListFromTree(arr, ValidationError.class);
  }

  static List<Warning> warnings(JsonArray arr) {
    return WireJson.projectedListFromTree(arr, Warning.class);
  }

  /** Parse proposed fixes; empty when absent, with every fix component required on the wire. */
  static List<Fix> fixes(JsonArray arr) {
    return WireJson.projectedListFromTree(arr, Fix.class);
  }

  /** Parse applied changes; absent optional change arrays remain null. */
  static List<Change> changes(JsonArray arr) {
    return WireJson.projectedListFromTree(arr, Change.class);
  }

  /** Parse an explain result, refusing missing or non-string required enum tokens. */
  static ExplanationTrace explanationTrace(JsonObject o) {
    return WireJson.projectedFromTree(o, ExplanationTrace.class);
  }

  /** Parse a JSON array of guide-breakdown entries into a typed list; empty when absent. */
  static List<GuideBreakdownEntry> guideBreakdown(JsonArray arr) {
    return WireJson.listFromTree(arr, GuideBreakdownEntry.class);
  }

  /** Parse collected notices, refusing a required flag that is not a JSON boolean. */
  static List<CollectedNotice> collectedNotices(JsonArray arr) {
    return WireJson.projectedListFromTree(arr, CollectedNotice.class);
  }

  /** Parse the aggregate wire shape into an {@link AggregateResult}. */
  static AggregateResult aggregateResult(JsonObject o) {
    return WireJson.projectedFromTree(o, AggregateResult.class);
  }
}
