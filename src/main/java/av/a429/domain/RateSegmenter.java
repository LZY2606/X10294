package av.a429.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Rate segmentation over adjacent-word intervals. Rate changes and
 * acquisition gaps keep multiple legal segment candidates; nothing is
 * collapsed to a single answer unless a reviewer decision does so.
 */
public final class RateSegmenter {

    public record TimedWord(String wordId, int seq, String channel, double rxTimeMs) {}

    public record Interval(String channel, String fromWordId, String toWordId,
                           int fromSeq, int toSeq, double dtMs) {}

    public record Candidate(String id, String channel, int startSeq, int endSeq,
                            String kind, double rateHz, String basis) {}

    public static final double[] NOMINAL_RATES_HZ = {100.0, 50.0, 25.0, 12.5, 10.0, 1.0};
    private static final double TOLERANCE = 0.25;

    private RateSegmenter() {}

    public static List<Interval> intervals(List<TimedWord> words) {
        List<Interval> out = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            TimedWord a = words.get(i - 1);
            TimedWord b = words.get(i);
            if (!a.channel().equals(b.channel())) {
                continue;
            }
            out.add(new Interval(a.channel(), a.wordId(), b.wordId(),
                    a.seq(), b.seq(), b.rxTimeMs() - a.rxTimeMs()));
        }
        return out;
    }

    /**
     * Builds candidates: one per nominal rate run, plus GAP candidates where
     * an interval exceeds gapFactor * channel median interval. Ambiguous
     * memberships are all retained.
     */
    public static List<Candidate> candidates(List<TimedWord> words, double gapFactor) {
        List<Candidate> out = new ArrayList<>();
        List<Interval> ivs = intervals(words);
        int n = 0;

        for (double rate : NOMINAL_RATES_HZ) {
            double period = 1000.0 / rate;
            int runStart = -1;
            for (int i = 0; i <= ivs.size(); i++) {
                boolean match = i < ivs.size()
                        && Math.abs(ivs.get(i).dtMs() - period) <= period * TOLERANCE;
                if (match && runStart < 0) {
                    runStart = i;
                }
                if (!match && runStart >= 0) {
                    Interval first = ivs.get(runStart);
                    Interval last = ivs.get(i - 1);
                    out.add(new Candidate("cand-" + (++n), first.channel(),
                            first.fromSeq(), last.toSeq(), "RATE", rate,
                            "interval-mean~" + rate + "Hz"));
                    runStart = -1;
                }
            }
        }

        if (!ivs.isEmpty()) {
            double[] sorted = ivs.stream().mapToDouble(Interval::dtMs).sorted().toArray();
            double median = sorted[sorted.length / 2];
            for (Interval iv : ivs) {
                if (iv.dtMs() > median * gapFactor && iv.dtMs() > 0) {
                    out.add(new Candidate("cand-" + (++n), iv.channel(),
                            iv.fromSeq(), iv.toSeq(), "GAP", 0.0,
                            "acquisition-gap " + iv.dtMs() + "ms > " + gapFactor + "x median " + median + "ms"));
                }
            }
        }
        return out;
    }

    /** Mean of aggregable engineering values inside [startSeq, endSeq]. Bad-parity words never enter. */
    public static Double aggregate(List<Engineering.Value> values, int startSeq, int endSeq,
                                   List<TimedWord> words) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < words.size(); i++) {
            TimedWord w = words.get(i);
            if (w.seq() < startSeq || w.seq() > endSeq) {
                continue;
            }
            Engineering.Value v = values.get(i);
            if (v.aggregable() && v.normalized() != null) {
                sum += v.normalized();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }
}
