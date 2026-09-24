package com.avionics.a429.domain;

/** Tunable parameters of rate segmentation. Versioned so revisions invalidate
 *  only the rate-derived layer. */
public record RateParams(long gapNanos, double switchRatio, double candidateTolerance,
                         int minIntervals) {

    public static RateParams defaults() {
        return new RateParams(100_000_000L, 0.25, 0.08, 2);
    }

    public static final double[] STANDARD_HZ = {12.5, 25.0, 50.0, 100.0};

    public String signature() {
        return gapNanos + "/" + switchRatio + "/" + candidateTolerance + "/" + minIntervals;
    }
}
