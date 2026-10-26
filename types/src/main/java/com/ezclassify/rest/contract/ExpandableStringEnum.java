package com.ezclassify.rest.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base of every contract enum: a string value from a set that a newer server can extend.
 *
 * <p>The API contract is open-response and closed-request. A server may add a value to any enum, so
 * a response carrying a value this SDK version does not know must still decode, and the caller must
 * be able to send it back unchanged, for example when assertions from one call feed the next. Each
 * subclass therefore keeps the raw wire token rather than mapping it onto a fixed Java {@code
 * enum}. The server, not this SDK, rejects a value a request may not carry.
 *
 * <p>This follows the Azure SDK's expandable string enum. Each subclass provides:
 *
 * <ul>
 *   <li>a public constant for every value the contract declares, such as {@code
 *       AssertionConfidence.INFERRED};
 *   <li>a lenient {@code fromString(String)} for tokens read from a response, which returns the
 *       canonical constant for a declared token and a new instance carrying any other token;
 *   <li>a strict {@code fromWire(String)} for tokens a caller supplies, which refuses an undeclared
 *       token;
 *   <li>{@code values()}, the declared constants in contract order;
 *   <li>a nested {@code Value} enum of the declared values plus {@code UNKNOWN}, returned by {@link
 *       #value()} so a caller can {@code switch} over it.
 * </ul>
 *
 * <p>Two instances are equal when they have the same class and the same token. Because {@code
 * fromString} returns the canonical constant for a declared token, {@code ==} also works for
 * declared values.
 *
 * @param <V> the nested enum of the values this SDK version knows, including {@code UNKNOWN}
 */
public abstract class ExpandableStringEnum<V extends Enum<V>> {

  private final String token;
  private final V value;
  private final boolean known;

  /**
   * Package-private so that only the contract types of this package extend it.
   *
   * @param token the wire token, exactly as it appears on the JSON API
   * @param value the matching declared value, or the subclass's {@code UNKNOWN} member
   * @param known whether {@code value} is a declared value rather than {@code UNKNOWN}
   */
  ExpandableStringEnum(String token, V value, boolean known) {
    this.token = Objects.requireNonNull(token, "token");
    this.value = Objects.requireNonNull(value, "value");
    this.known = known;
  }

  /**
   * Index declared constants by their wire tokens, keeping contract order.
   *
   * @param constants the declared constants of one subclass
   * @param <T> the subclass
   * @return an unmodifiable, insertion-ordered map from wire token to constant
   * @throws IllegalArgumentException if two constants share a token
   */
  @SafeVarargs
  static <T extends ExpandableStringEnum<?>> Map<String, T> index(T... constants) {
    Map<String, T> byToken = new LinkedHashMap<>();
    for (T constant : constants) {
      if (byToken.putIfAbsent(constant.toWire(), constant) != null) {
        throw new IllegalArgumentException("Duplicate wire token: " + constant.toWire());
      }
    }
    return Collections.unmodifiableMap(byToken);
  }

  /**
   * The wire token, exactly as the server sent it or the caller supplied it. A token this SDK
   * version does not know is returned unchanged, so a value read from one response can be sent in
   * the next request.
   *
   * @return the raw wire token, never {@code null}
   */
  public final String toWire() {
    return token;
  }

  /**
   * The value as this SDK version understands it, for use in a {@code switch}.
   *
   * @return the matching declared value, or {@code UNKNOWN} when this SDK version does not know the
   *     token
   */
  public final V value() {
    return value;
  }

  /**
   * Whether the token is one this SDK version declares.
   *
   * @return {@code true} for a declared value, {@code false} for one a newer server added
   */
  public final boolean isKnown() {
    return known;
  }

  /**
   * Returns the raw wire token, the same as {@link #toWire()}.
   *
   * @return the raw wire token
   */
  @Override
  public final String toString() {
    return token;
  }

  /**
   * Two contract enum values are equal when they have the same type and the same wire token.
   *
   * @param other the object to compare with
   * @return whether {@code other} is the same type with the same token
   */
  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null || other.getClass() != getClass()) return false;
    return token.equals(((ExpandableStringEnum<?>) other).token);
  }

  /**
   * Hash of the wire token, consistent with {@link #equals(Object)}.
   *
   * @return the token's hash code
   */
  @Override
  public final int hashCode() {
    return token.hashCode();
  }
}
