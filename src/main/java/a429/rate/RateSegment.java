package a429.rate;

import java.util.List;

/** A sequence segment with all its legal rate candidates. */
public record RateSegment(
        String segmentId,
        String channel,
        int fromSeq,
        int toSeq,
        long startMicros,
        long endMicros,
        boolean gapBefore,
        long gapMicros,
        List<RateCandidate> candidates
) { }
