package org.avionics.review.domain;

/**
 * One legal link-rate explanation for a segment. Ambiguous segments carry more than one.
 *
 * @param linkRate       "100 kbit/s" or "12.5 kbit/s"
 * @param wordPeriodMicros expected word time
 * @param observedMinGapMicros smallest inter-word gap in the segment
 * @param residualFraction |n*period - minGap| / minGap at the best multiple
 * @param reason         machine-readable explanation
 */
public record RateCandidate(String linkRate, double wordPeriodMicros, double observedMinGapMicros,
                            double residualFraction, String reason) {
}
