package com.ezclassify.rest.client;

/**
 * Immutable choices for one scan operation.
 *
 * <p>Null omits an option.
 *
 * @param inputFormat The declared input view or form; null lets the server recognize it.
 * @param outputFormat The declared output address; null uses the server default.
 */
public record ScanOptions(String inputFormat, String outputFormat) {
  /**
   * Omit every optional choice.
   *
   * @return the default options
   */
  public static ScanOptions defaults() {
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

    private String inputFormat;
    private String outputFormat;

    /**
     * The declared input view or form; null lets the server recognize it.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder inputFormat(String value) {
      this.inputFormat = value;
      return this;
    }

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
     * Snapshot these choices for an operation.
     *
     * @return immutable options
     */
    public ScanOptions build() {
      return new ScanOptions(inputFormat, outputFormat);
    }
  }
}
