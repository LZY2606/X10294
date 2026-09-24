package a429.rate;

import java.util.List;

/**
 * Full rate review result for one (channel,label) key: per-word intervals and
 * the legal segmentation candidates.
 */
public record RateAnalysis(
        String keyId,
        String channelId,
        String labelOctal,
        List<Interval> intervals,
        List<RateCandidate> candidates,
        String selectedCandidateId,
        double gapFactor,
        double switchRatio) {
}
