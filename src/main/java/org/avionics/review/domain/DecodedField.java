package org.avionics.review.domain;

import java.util.List;

/**
 * One decoded field of a word.
 *
 * @param value     display value (octal label string, binary SDI/SSM string, BCD digits ...)
 * @param bits      1-based wire bit numbers this field occupies (under the chosen orientation)
 * @param diagnostic null when the field is clean
 */
public record DecodedField(String name, String value, List<Integer> bits, String diagnostic) {
}
