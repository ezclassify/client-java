package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.RenderPreservation;

/**
 * Immutable choices for one render operation.
 *
 * <p>Null omits an option.
 *
 * @param outputFormat The declared rendering output address; null uses the server default.
 * @param preservation The required preservation behavior; null uses the server default.
 */
public record RenderOptions(String outputFormat, RenderPreservation preservation) {
  /**
   * Omit every optional choice.
   *
   * @return the default options
   */
  public static RenderOptions defaults() {
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
    private RenderPreservation preservation;

    /**
     * The declared rendering output address; null uses the server default.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder outputFormat(String value) {
      this.outputFormat = value;
      return this;
    }

    /**
     * The required preservation behavior; null uses the server default.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder preservation(RenderPreservation value) {
      this.preservation = value;
      return this;
    }

    /**
     * Snapshot these choices for an operation.
     *
     * @return immutable options
     */
    public RenderOptions build() {
      return new RenderOptions(outputFormat, preservation);
    }
  }
}
