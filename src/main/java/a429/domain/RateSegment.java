package a429.domain;

import java.util.List;

/**
 * A contiguous rate segment of one channel. Every legal speed candidate is
 * retained; {@code unique} is true only when exactly one speed fits all intervals.
 * GAP segments mark acquisition gaps and carry no speed candidates.
 */
public record RateSegment(
        String segmentId,
        String channelId,
        int index,
        String kind,
        long startSeq,
        long endSeq,
        double startEpochMs,
        double endEpochMs,
        double meanIntervalMs,
        List<Integer> candidateSpeedsBps,
        boolean unique,
        List<String> intervalIds,
        List<String> notes
) {
}
