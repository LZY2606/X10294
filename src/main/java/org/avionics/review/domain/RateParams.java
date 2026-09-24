package org.avionics.review.domain;

/**
 * Revisable rate-analysis parameters.
 *
 * @param highRateWordMicros  word time of the 100 kbit/s link (32 bits at 100 kHz)
 * @param lowRateWordMicros   word time of the 12.5 kbit/s link
 * @param gapThresholdMicros  inter-word gap at/above which an acquisition gap is declared
 * @param toleranceFraction   tolerance on period multiples for candidate matching
 */
public record RateParams(String version, double highRateWordMicros, double lowRateWordMicros,
                         double gapThresholdMicros, double toleranceFraction) {

    public static RateParams v1() {
        return new RateParams("rate-params-v1", 320.0, 2560.0, 5000.0, 0.15);
    }
}
