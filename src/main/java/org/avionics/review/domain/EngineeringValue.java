package org.avionics.review.domain;

import java.util.List;

/**
 * Engineering interpretation of a word.
 *
 * @param value      numeric value for BNR/BCD; null when unconvertible
 * @param unit       engineering unit / coordinate datum ("unit or coordinate reference")
 * @param discrete   signal name -> bit state for DISCRETE words
 * @param diagnostics reasons the value could not be converted; the raw word stays in place
 */
public record EngineeringValue(Double value, String unit, List<BitSignal> discrete,
                               List<String> diagnostics) {
    public record BitSignal(String name, int bit, int state) {
    }

    public boolean usableForAggregate() {
        return diagnostics == null || diagnostics.isEmpty();
    }
}
