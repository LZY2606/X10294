package arinc;

import java.util.ArrayList;
import java.util.List;

/**
 * Link-rate segmentation. Inter-word gaps are classified against the two
 * ARINC 429 rates (high 100 kbps -> 360 us word interval, low 12.5 kbps ->
 * 2.88 ms). Rate changes and acquisition gaps keep segmentation candidates;
 * nothing is decided irreversibly at import time.
 */
public final class Segmenter {

    public static final long HIGH_INTERVAL_NS = 360_000;      // 100 kbps, 36 bit-times
    public static final long LOW_INTERVAL_NS = 2_880_000;     // 12.5 kbps, 36 bit-times
    public static final long GAP_THRESHOLD_NS = 4 * LOW_INTERVAL_NS; // acquisition gap
    private static final double TOL = 0.35;

    public record WordTiming(long wordId, int seq, long rxTimeNs, int channel) {}

    public record SegmentCandidate(
            int startSeq, int endSeq, long rateHz, int rank, String rationale) {}

    enum Gap { HIGH, LOW, AMBIGUOUS, ACQUISITION_GAP }

    static Gap classify(long gapNs) {
        if (gapNs >= GAP_THRESHOLD_NS) return Gap.ACQUISITION_GAP;
        if (Math.abs(gapNs - HIGH_INTERVAL_NS) <= HIGH_INTERVAL_NS * TOL) return Gap.HIGH;
        if (Math.abs(gapNs - LOW_INTERVAL_NS) <= LOW_INTERVAL_NS * TOL) return Gap.LOW;
        return Gap.AMBIGUOUS;
    }

    static long rateOf(Gap g) {
        return g == Gap.HIGH ? 100_000L : 12_500L;
    }

    /**
     * Build candidate segmentations for one channel's word timings.
     * Rank 0 candidates form the primary segmentation; rank >= 1 are
     * alternatives kept because the evidence is not unique.
     */
    public static List<SegmentCandidate> segment(List<WordTiming> words) {
        List<SegmentCandidate> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        int n = words.size();
        Gap[] gaps = new Gap[Math.max(0, n - 1)];
        for (int i = 0; i < n - 1; i++) {
            gaps[i] = classify(words.get(i + 1).rxTimeNs() - words.get(i).rxTimeNs());
        }
        // Primary segmentation: split on acquisition gaps and rate changes.
        int segStart = 0;
        Gap current = null;
        for (int i = 0; i < n - 1; i++) {
            Gap g = gaps[i];
            if (g == Gap.ACQUISITION_GAP) {
                out.add(new SegmentCandidate(words.get(segStart).seq(), words.get(i).seq(),
                        current == null ? 0 : rateOf(current), 0,
                        "segment closed by acquisition gap of "
                                + (words.get(i + 1).rxTimeNs() - words.get(i).rxTimeNs()) + " ns"));
                // alternative: merge across the gap (words may be missing, rate unchanged)
                if (i + 2 < n && current != null) {
                    Gap after = gaps[i + 1];
                    if (after == current || after == Gap.AMBIGUOUS) {
                        out.add(new SegmentCandidate(words.get(segStart).seq(), words.get(i + 2).seq(),
                                rateOf(current), 1,
                                "merge candidate: acquisition gap treated as missing words, rate unchanged"));
                    }
                }
                segStart = i + 1;
                current = null;
                continue;
            }
            Gap eff = (g == Gap.AMBIGUOUS) ? (current != null ? current : Gap.LOW) : g;
            if (g == Gap.AMBIGUOUS) {
                // ambiguous gap: alternative boundary placement candidate
                out.add(new SegmentCandidate(words.get(segStart).seq(), words.get(i).seq(),
                        current == null ? rateOf(Gap.LOW) : rateOf(current), 1,
                        "ambiguous inter-word gap " +
                                (words.get(i + 1).rxTimeNs() - words.get(i).rxTimeNs())
                                + " ns: boundary placement not uniquely determined"));
            }
            if (current == null) {
                current = eff;
            } else if (eff != current) {
                out.add(new SegmentCandidate(words.get(segStart).seq(), words.get(i).seq(),
                        rateOf(current), 0, "rate segment ended by rate switch to "
                        + rateOf(eff) + " bps"));
                segStart = i + 1;
                current = eff;
            }
        }
        out.add(new SegmentCandidate(words.get(segStart).seq(), words.get(n - 1).seq(),
                current == null ? 0 : rateOf(current), 0, "final segment"));
        return out;
    }
}
