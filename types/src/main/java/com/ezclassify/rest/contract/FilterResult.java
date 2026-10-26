package com.ezclassify.rest.contract;

import java.util.List;
import java.util.Map;

/**
 * Substring-filter result over concepts or guide rules: which ids matched, per-category counts, and
 * the global visible/total tally.
 *
 * @param visibleIds ids of the concepts or rules that matched the query
 * @param categoryCounts how many entries matched in each category, keyed by category id
 * @param total how many entries matched across all categories
 */
public record FilterResult(
    List<String> visibleIds, Map<String, Count> categoryCounts, Count total) {
  /**
   * Visible/total pair for a filter bucket.
   *
   * @param visible how many entries in the bucket matched the query
   * @param total how many entries the bucket holds before filtering
   */
  public record Count(int visible, int total) {}
}
