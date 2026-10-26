package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.ContextCrosswalkTranslationResult;
import com.ezclassify.rest.contract.CrosswalkTranslationResult;
import com.google.gson.JsonObject;
import java.util.List;

/** Directional crosswalk operations bound to one immutable server-resolved Context composition. */
public final class ContextCrosswalk {
  private final EzClassifyContext ctx;

  ContextCrosswalk(EzClassifyContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Translate a source marking through a crosswalk declared by the context.
   *
   * @param crosswalkId crosswalk artifact id declared by the context
   * @param marking source marking text
   * @return the completed or forbidden translation under the resolved context envelope
   * @throws IllegalArgumentException if a required argument is null or empty
   */
  public ContextCrosswalkTranslationResult translate(String crosswalkId, String marking) {
    Args.requiredId(crosswalkId, "crosswalkId");
    Args.required(marking, "marking");
    JsonObject body = new JsonObject();
    Json.put(body, "crosswalkId", crosswalkId);
    Json.put(body, "marking", marking);
    return translate(body);
  }

  /**
   * Translate structured source assertions through a crosswalk declared by the context.
   *
   * @param crosswalkId crosswalk artifact id declared by the context
   * @param assertions structured source assertions
   * @return the completed or forbidden translation under the resolved context envelope
   * @throws IllegalArgumentException if a required argument is null or empty
   */
  public ContextCrosswalkTranslationResult translate(
      String crosswalkId, List<Assertion> assertions) {
    Args.requiredId(crosswalkId, "crosswalkId");
    Args.requiredList(assertions, "assertions");
    JsonObject body = new JsonObject();
    Json.put(body, "crosswalkId", crosswalkId);
    Json.put(body, "assertions", ContextJson.assertionArray(assertions));
    return translate(body);
  }

  private ContextCrosswalkTranslationResult translate(JsonObject body) {
    return ContextJson.project(
        ctx.postResult(
            "crosswalk/translate",
            body,
            result -> WireJson.fromTree(result, CrosswalkTranslationResult.class)),
        ContextCrosswalkTranslationResult::new);
  }
}
