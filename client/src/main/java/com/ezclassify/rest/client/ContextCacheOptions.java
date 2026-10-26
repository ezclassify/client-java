package com.ezclassify.rest.client;

/**
 * Response-cache choices for one Context binding.
 *
 * @param cacheAccessDecisions permit repeated access checks to return without a server audit
 *     record; requires a concrete Context version
 * @param disableCache send every operation to the server, including normally cached scheme reads
 */
public record ContextCacheOptions(boolean cacheAccessDecisions, boolean disableCache) {
  /**
   * Use bounded scheme caching while sending each access decision to the server.
   *
   * @return the default cache choices
   */
  public static ContextCacheOptions defaults() {
    return new ContextCacheOptions(false, false);
  }
}
