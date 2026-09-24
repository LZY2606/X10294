package com.avionics.a429.domain;

import java.util.List;

/**
 * One candidate contiguous run of same-label words interpreted as a constant
 * link rate. Multiple candidates are kept explicitly when timing is ambiguous.
 */
public final class RateSegment {
    public final String id;
    public final String channel;
    public final String labelOctal;
    public final int startSequence;
    public final int endSequence;
    public final int wordCount;
    public final long meanIntervalNanos;
    public final double measuredHz;
    public final List<Double> candidateHz;
    public final String reason;
    public final boolean acquisitionGap;

    public RateSegment(String id, String channel, String labelOctal, int startSequence,
                       int endSequence, int wordCount, long meanIntervalNanos,
                       double measuredHz, List<Double> candidateHz, String reason,
                       boolean acquisitionGap) {
        this.id = id;
        this.channel = channel;
        this.labelOctal = labelOctal;
        this.startSequence = startSequence;
        this.endSequence = endSequence;
        this.wordCount = wordCount;
        this.meanIntervalNanos = meanIntervalNanos;
        this.measuredHz = measuredHz;
        this.candidateHz = candidateHz;
        this.reason = reason;
        this.acquisitionGap = acquisitionGap;
    }
}
