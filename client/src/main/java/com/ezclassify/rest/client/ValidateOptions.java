package com.ezclassify.rest.client;

import com.ezclassify.rest.contract.ProcessingContext;
import java.util.List;

/**
 * Immutable choices for one validate operation.
 *
 * <p>Null omits an option; empty lists and false remain explicit wire choices.
 *
 * @param inputFormat The declared input view or form; null lets the server recognize it.
 * @param outputFormat The declared output address; null uses the server default.
 * @param surface The declared surface; null leaves the server default.
 * @param context The caller-supplied marking role and whole-marking context.
 * @param parts Part markings the whole must cover.
 */
public record ValidateOptions(
    String inputFormat,
    String outputFormat,
    String surface,
    ProcessingContext context,
    List<String> parts) {
  /**
   * Snapshot caller-owned collections and nested contract values.
   *
   * @param inputFormat The declared input view or form; null lets the server recognize it.
   * @param outputFormat The declared output address; null uses the server default.
   * @param surface The declared surface; null leaves the server default.
   * @param context The caller-supplied marking role and whole-marking context.
   * @param parts Part markings the whole must cover.
   */
  public ValidateOptions {
    context = OptionSnapshots.context(context);
    parts = parts == null ? null : List.copyOf(parts);
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
  public static ValidateOptions defaults() {
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
    private String surface;
    private ProcessingContext context;
    private List<String> parts;

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
     * Part markings the whole must cover.
     *
     * @param value the option value, or null to omit it
     * @return this builder
     */
    public Builder parts(List<String> value) {
      this.parts = value;
      return this;
    }

    /**
     * Snapshot these choices for an operation.
     *
     * @return immutable options
     */
    public ValidateOptions build() {
      return new ValidateOptions(inputFormat, outputFormat, surface, context, parts);
    }
  }
}
