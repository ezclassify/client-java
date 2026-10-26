package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.ContextGuideEvaluationResult;
import com.ezclassify.rest.contract.ContextGuideFilterRulesResult;
import com.ezclassify.rest.contract.ContextGuideRuleDescriptionResult;
import com.ezclassify.rest.contract.ContextGuideScanResult;
import com.ezclassify.rest.contract.ContextGuideViewResult;
import com.ezclassify.rest.contract.EvaluationResult;
import com.ezclassify.rest.contract.GuideScanResult;
import com.ezclassify.rest.contract.GuideViewResponse;
import com.ezclassify.rest.contract.RuleDescription;
import com.google.gson.JsonObject;

/**
 * Guide-domain operations bound to an {@link EzClassifyContext}. Calls route through {@code
 * /v1/ctx/{ctxId}/{version}/guide/*}; bodies carry payload only.
 */
public final class ContextGuide {

  private final EzClassifyContext ctx;

  ContextGuide(EzClassifyContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Scan free text for guide rule matches across the context's guides.
   *
   * @param text the free text to scan
   * @return one result per matching rule, with the matched keywords and spans
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextGuideScanResult scan(String text) {
    return scan(text, null);
  }

  /**
   * Scan free text for guide rule matches, narrowed to one guide.
   *
   * @param text the free text to scan
   * @param guideId the guide to scan within, or {@code null} for all guides
   * @return one result per matching rule, with the matched keywords and spans
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextGuideScanResult scan(String text, String guideId) {
    Args.required(text, "text");
    JsonObject body = new JsonObject();
    Json.put(body, "text", text);
    if (guideId != null) Json.put(body, "guideId", guideId);
    return ContextJson.project(
        ctx.postResult(
            "guide/scan", body, value -> WireJson.listFromTree(value, GuideScanResult.class)),
        ContextGuideScanResult::new);
  }

  /**
   * Evaluate a guide with the given rule selection.
   *
   * @param guideId the guide to evaluate
   * @param ruleIds the ids of the rules to select for the evaluation
   * @return the evaluation result, with the aggregated marking and the rules that were selected and
   *     computed as active
   * @throws IllegalArgumentException if a required argument is null or empty
   */
  public ContextGuideEvaluationResult evaluate(String guideId, String... ruleIds) {
    Args.requiredId(guideId, "guideId");
    Args.required(ruleIds, "ruleIds");
    JsonObject body = new JsonObject();
    Json.put(body, "guideId", guideId);
    Json.put(body, "selectedRuleIds", Json.stringArray(ruleIds));
    return ContextJson.project(
        ctx.postResult("guide/evaluate", body, value -> evaluationResult(value.getAsJsonObject())),
        ContextGuideEvaluationResult::new);
  }

  /**
   * Evaluate selected rules across every guide in the Context.
   *
   * @param ruleIds the ids of the rules to select across the guide set
   * @return the evaluation result, with the combined marking and contributing rules
   * @throws IllegalArgumentException if the rule id array is null
   */
  public ContextGuideEvaluationResult evaluateMany(String... ruleIds) {
    Args.required(ruleIds, "ruleIds");
    JsonObject body = new JsonObject();
    body.addProperty("allGuides", true);
    Json.put(body, "selectedRuleIds", Json.stringArray(ruleIds));
    return ContextJson.project(
        ctx.postResult("guide/evaluate", body, value -> evaluationResult(value.getAsJsonObject())),
        ContextGuideEvaluationResult::new);
  }

  /**
   * Substring-filter the context's guide rules.
   *
   * @param query the substring to filter rule names by
   * @return the matching rule ids and the visible/total counts per category
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextGuideFilterRulesResult filterRules(String query) {
    return filterRules(query, null);
  }

  /**
   * Substring-filter guide rules, narrowed to one guide.
   *
   * @param query the substring to filter rule names by
   * @param guideId the guide to filter within, or {@code null} for all guides
   * @return the matching rule ids and the visible/total counts per category
   * @throws IllegalArgumentException if a required argument is null
   */
  public ContextGuideFilterRulesResult filterRules(String query, String guideId) {
    Args.required(query, "query");
    JsonObject body = new JsonObject();
    Json.put(body, "query", query);
    if (guideId != null) Json.put(body, "guideId", guideId);
    return ContextJson.project(
        ctx.postResult(
            "guide/filter-rules",
            body,
            value -> ContextScheme.filterResult(value.getAsJsonObject())),
        ContextGuideFilterRulesResult::new);
  }

  /**
   * Describe a single guide rule.
   *
   * @param ruleId the id of the guide rule to describe
   * @return the rule description, with its outcome text and dependency and override relationships
   * @throws IllegalArgumentException if a required argument is null or empty
   */
  public ContextGuideRuleDescriptionResult describeRule(String ruleId) {
    return describeRule(ruleId, null);
  }

  /**
   * Describe a single guide rule in a specific guide.
   *
   * @param ruleId the id of the guide rule to describe
   * @param guideId the guide the rule belongs to, or {@code null} to search all guides
   * @return the rule description, with its outcome text and dependency and override relationships
   * @throws IllegalArgumentException if a required argument is null or empty
   */
  public ContextGuideRuleDescriptionResult describeRule(String ruleId, String guideId) {
    Args.requiredId(ruleId, "ruleId");
    JsonObject body = new JsonObject();
    Json.put(body, "ruleId", ruleId);
    if (guideId != null) Json.put(body, "guideId", guideId);
    return ContextJson.project(
        ctx.postResult(
            "guide/describe-rule", body, value -> WireJson.fromTree(value, RuleDescription.class)),
        ContextGuideRuleDescriptionResult::new);
  }

  /**
   * Build a render-ready structural view of the context's first guide.
   *
   * @return the structural view, with the guide's rules grouped by category
   */
  public ContextGuideViewResult view() {
    return view(null);
  }

  /**
   * Build a render-ready structural view of a specific guide.
   *
   * @param guideId the guide to view, or {@code null} for the context's first guide
   * @return the structural view, with the guide's rules grouped by category
   */
  public ContextGuideViewResult view(String guideId) {
    JsonObject body = new JsonObject();
    if (guideId != null) Json.put(body, "guideId", guideId);
    return ContextJson.project(
        ctx.postResult(
            "guide/view", body, value -> WireJson.fromTree(value, GuideViewResponse.class)),
        ContextGuideViewResult::new);
  }

  private static EvaluationResult evaluationResult(JsonObject v) {
    return WireJson.fromTree(v, EvaluationResult.class);
  }
}
