package org.avionics.review.domain;

/** Gap between two consecutive in-place words of one channel. */
public record Interval(String fromWordId, String toWordId, long gapNanos, boolean acquisitionGap) {
    public double gapMicros() {
        return gapNanos / 1000.0;
    }
}
