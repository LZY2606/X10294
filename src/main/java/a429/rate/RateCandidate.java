package a429.rate;

import java.util.List;

/**
 * One legal segment candidate. Ambiguous segments keep every legal candidate;
 * the adjudication layer decides which (if any) is accepted.
 */
public record RateCandidate(
        String candidateId,
        String segmentId,
        String channel,
        int fromSeq,
        int toSeq,
        int wordCount,
        double measuredBps,
        int claimedBps,
        double toleranceRatio,
        List<Integer> legalBps,
        String kind,           // RATE_SWITCH / ACQUISITION_GAP / STEADY
        String reason
) { }
