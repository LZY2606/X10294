package a429.domain;

import java.util.List;

/** One interval or acquisition gap between adjacent words of one channel. */
public record Interval(
        String intervalId,
        String fromWordId,
        String toWordId,
        long fromSeq,
        long toSeq,
        double deltaMs,
        List<Integer> compatibleSpeedsBps,
        List<Integer> wordPeriodMultiples,
        boolean acquisitionGap,
        List<String> notes
) {
}
