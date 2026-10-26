package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.ProcessingContext;

/**
 * Immutable choices for one facts-based aggregate operation.
 *
 * <p>Null omits an option; empty lists and false remain explicit wire choices.
 *
 * @param outputFormat The declared output address; null uses the server default.
 * @param surface The declared surface; null leaves the server default.
 * @param context The caller-supplied marking role and whole-marking context.
 */
public record FactsAggregateOptions(
    String outputFormat, String surface, ProcessingContext context) {
  /**
   * Snapshot caller-owned collections and nested contract values.
   *
   * @param outputFormat The declared output address; null uses the server default.
   * @param surface The declared surface; null leaves the server default.
   * @param context The caller-supplied marking role and whole-marking context.
   */
  public FactsAggregateOptions {
    context = OptionSnapshots.context(context);
  }

  /**
   * Return a defensive copy of context.
   *
   * @return the option, or null when omitted
   */
  @Override
  public ProcessingContext context() {
    return OptionSnapshots.context(context);
  }

  /**
   * Omit every optional choice.
   *
   * @return the default options
   */
  public static FactsAggregateOptions defaults() {
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
    private ProcessingContext context;

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
     * The caller-supplied marking role and whole-marking context.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder context(ProcessingContext value) {
      this.context = value;
      return this;
    }

    /**
     * Snapshot these choices for an operation.
     *
     * @return immutable options
     */
    public FactsAggregateOptions build() {
      return new FactsAggregateOptions(outputFormat, surface, context);
    }
  }
}
