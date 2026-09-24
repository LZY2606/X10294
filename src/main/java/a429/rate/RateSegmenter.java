package a429.rate;

import a429.domain.RawWord;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a per-key ordered word stream into adjacent intervals and legal rate
 * segmentation candidates.
 *
 * Rules (parameters come from the branch's rate parameters, never hard-coded):
 *  - gap boundary: interval &gt; gapFactor * medianInterval
 *  - rate switch: interval changes by &gt;= switchRatio relative to its predecessor
 *  - an interval inside the +/- {@link #AMBIGUITY_BAND} band of a threshold is
 *    ambiguous: segmentations with and without that boundary are both emitted
 * Bad-parity words are still received, so they participate in timing; they are
 * only excluded from engineering aggregates.
 */
public final class RateSegmenter {

    public static final double AMBIGUITY_BAND = 0.10d;

    public RateAnalysis analyze(String keyId, String channelId, String labelOctal,
                                List<RawWord> words, double gapFactor, double switchRatio,
                                String selectedCandidateId) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            RawWord a = words.get(i - 1);
            RawWord b = words.get(i);
            double gapMs = (b.receivedAtNs() - a.receivedAtNs()) / 1_000_000.0d;
            intervals.add(new Interval(
                    keyId + ":i" + (i - 1), channelId, labelOctal,
                    a.position(), b.position(), a.receivedAtNs(), b.receivedAtNs(), gapMs));
        }

        long median = words.size() < 3 ? (intervals.isEmpty() ? 0 : (long) median(intervals))
                : (long) median(intervals);

        List<Boundary> boundaries = classify(intervals, median, gapFactor, switchRatio);
        List<List<Boundary>> variants = variants(boundaries);
        List<RateCandidate> candidates = new ArrayList<>();
        for (int v = 0; v < variants.size(); v++) {
            candidates.addAll(buildSegments(keyId, channelId, labelOctal, words,
                    intervals, variants.get(v), v, variants.size() > 1));
        }
        return new RateAnalysis(keyId, channelId, labelOctal, intervals, candidates,
                selectedCandidateId, gapFactor, switchRatio);
    }

    private List<Boundary> classify(List<Interval> intervals, long median,
                                    double gapFactor, double switchRatio) {
        List<Boundary> out = new ArrayList<>();
        double gapThreshold = median * gapFactor / 1_000_000.0d;
        for (int i = 0; i < intervals.size(); i++) {
            double gap = intervals.get(i).gapMs();
            boolean gapHit = median > 0 && gap > gapThreshold;
            boolean gapNear = median > 0 && near(gap, gapThreshold);

            boolean switchHit = false;
            boolean switchNear = false;
            double ratio = 0;
            if (i > 0) {
                double prev = intervals.get(i - 1).gapMs();
                if (prev > 0) {
                    ratio = Math.max(gap / prev, prev / gap);
                    switchHit = ratio >= switchRatio;
                    switchNear = near(ratio, switchRatio);
                }
            }

            if (gapHit || switchHit || gapNear || switchNear) {
                boolean forced = (gapHit && !gapNear) || (switchHit && !switchNear)
                        || (gapHit && i == 0);
                boolean ambiguous = (gapNear || switchNear) && !forced;
                String reason;
                if (gapHit || gapNear) {
                    reason = "gap " + fmt(gap) + "ms vs threshold " + fmt(gapThreshold)
                            + "ms (factor " + gapFactor + ")";
                } else {
                    reason = "interval ratio " + fmt(ratio) + " vs switch " + switchRatio;
                }
                out.add(new Boundary(i, forced, ambiguous, reason, gapHit));
            }
        }
        return out;
    }

    private List<List<Boundary>> variants(List<Boundary> boundaries) {
        List<List<Boundary>> out = new ArrayList<>();
        out.add(new ArrayList<>());
        for (Boundary b : boundaries) {
            if (b.forced) {
                for (List<Boundary> v : out) {
                    v.add(b);
                }
            } else if (b.ambiguous) {
                List<List<Boundary>> grown = new ArrayList<>();
                for (List<Boundary> v : out) {
                    List<Boundary> with = new ArrayList<>(v);
                    with.add(b);
                    grown.add(v);
                    grown.add(with);
                }
                out = grown;
            }
        }
        return out;
    }

    private List<RateCandidate> buildSegments(String keyId, String channelId, String labelOctal,
                                              List<RawWord> words, List<Interval> intervals,
                                              List<Boundary> chosen, int variant,
                                              boolean ambiguous) {
        List<RateCandidate> segs = new ArrayList<>();
        int start = 0;
        int segIndex = 0;
        for (Boundary b : chosen) {
            addSegment(segs, keyId, channelId, labelOctal, words, intervals,
                    start, b.intervalIndex, segIndex++, variant, ambiguous, false);
            start = b.intervalIndex + 1;
        }
        addSegment(segs, keyId, channelId, labelOctal, words, intervals,
                start, intervals.size() - 1, segIndex, variant, ambiguous,
                chosen.stream().anyMatch(b -> b.gapEndsBefore));
        return segs;
    }

    private void addSegment(List<RateCandidate> out, String keyId, String channelId,
                            String labelOctal, List<RawWord> words, List<Interval> intervals,
                            int startWord, int lastInterval, int segIndex, int variant,
                            boolean ambiguous, boolean gapAtEnd) {
        int endWord = lastInterval + 1;
        long span = words.get(endWord).receivedAtNs() - words.get(startWord).receivedAtNs();
        double periodMs = span / 1_000_000.0d / (endWord - startWord);
        double rateHz = periodMs > 0 ? 1000.0d / periodMs : 0;
        String id = keyId + ":c" + variant + "s" + segIndex;
        List<int[]> bounds = List.of(new int[]{words.get(startWord).position(),
                words.get(endWord).position()});
        out.add(new RateCandidate(id, channelId, labelOctal, segIndex,
                words.get(startWord).position(), words.get(endWord).position(),
                rateHz, periodMs,
                "segment over positions " + words.get(startWord).position()
                        + ".." + words.get(endWord).position(),
                ambiguous, bounds, gapAtEnd));
    }

    private static double median(List<Interval> intervals) {
        List<Double> v = intervals.stream().map(Interval::gapMs).sorted().toList();
        return v.get(v.size() / 2);
    }

    private static boolean near(double value, double threshold) {
        return Math.abs(value - threshold) <= threshold * AMBIGUITY_BAND;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private record Boundary(int intervalIndex, boolean forced, boolean ambiguous,
                            String reason, boolean gapEndsBefore) {
    }
}
