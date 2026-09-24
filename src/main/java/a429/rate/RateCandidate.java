package a429.rate;

import java.util.List;

/**
 * One legal segmentation candidate of a label stream.
 * A candidate may stay ambiguous (ambiguous=true) until a human adjudication
 * selects one; all legal candidates are retained.
 */
public record RateCandidate(
        String id,
        String channelId,
        String labelOctal,
        int segmentIndex,
        int fromPosition,
        int toPosition,
        double rateHz,
        double periodMs,
        String reason,
        boolean ambiguous,
        List<int[]> boundaries,
        boolean gapAtEnd) {
}
