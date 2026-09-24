package org.avionics.review.domain;

import java.util.List;

public record RateSegment(
        String sid,
        String candidateMethod,
        String channel,
        int startIndex,
        int endIndex,
        double startMs,
        double endMs,
        int wordCount,
        double rateKhz,
        boolean gapBefore,
        List<Interval> intervals) {
}
