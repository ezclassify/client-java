package com.ezclassify.rest.client;

/**
 * Immutable choices for one facts-based suggest operation.
 *
 * <p>Null omits an option; empty lists and false remain explicit wire choices.
 *
 * @param outputFormat The declared output address; null uses the server default.
 * @param surface The declared surface; null leaves the server default.
 * @param filter Text to narrow the available suggestions.
 * @param includeGated Whether to include choices with unmet prerequisites; null uses the server
 *     default.
 */
public record FactsSuggestOptions(
    String outputFormat, String surface, String filter, Boolean includeGated) {
  /**
   * Omit every optional choice.
   *
   * @return the default options
   */
  public static FactsSuggestOptions defaults() {
    return builder().build();
  }

  /**
   * Start an operation-options builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable builder whose build method returns an immutable snapshot. */
  public static final class Builder {
    private Builder() {}

    private String outputFormat;
    private String surface;
    private String filter;
    private Boolean includeGated;

    /**
     * The declared output address; null uses the server default.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder outputFormat(String value) {
      this.outputFormat = value;
      return this;
    }

    /**
     * The declared surface; null leaves the server default.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder surface(String value) {
      this.surface = value;
      return this;
    }

    /**
     * Text to narrow the available suggestions.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder filter(String value) {
      this.filter = value;
      return this;
    }

    /**
     * Whether to include choices with unmet prerequisites; null uses the server default.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder includeGated(Boolean value) {
      this.includeGated = value;
      return this;
    }

    /**
     * Snapshot these choices for an operation.
     *
     * @return immutable options
     */
    public FactsSuggestOptions build() {
      return new FactsSuggestOptions(outputFormat, surface, filter, includeGated);
    }
  }
}
