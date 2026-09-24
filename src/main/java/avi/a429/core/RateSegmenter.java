package avi.a429.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one channel's word stream into rate segments. ARINC 429 word time is
 * 36 bit-times (32 bits + 4 bit gap): LOW_SPEED 12.5 kbit/s = 2.88 ms/word,
 * HIGH_SPEED 100 kbit/s = 0.36 ms/word.
 *
 * Rate changes and acquisition gaps produce segment boundaries; every segment
 * keeps all legal rate candidates until a human ruling accepts one. Words with
 * bad parity never enter the aggregated interval statistics.
 */
public final class RateSegmenter {

    public static final double LOW_HZ = 12_500.0;
    public static final double HIGH_HZ = 100_000.0;
    public static final long LOW_PERIOD_NS = 2_880_000L;   // 36 / 12500 s
    public static final long HIGH_PERIOD_NS = 360_000L;    // 36 / 100000 s
    /** Interval beyond this is treated as an acquisition gap, not a rate. */
    public static final long GAP_THRESHOLD_NS = LOW_PERIOD_NS * 3;

    public enum Kind { LOW, HIGH, GAP, UNKNOWN }

    /** One observed interval between adjacent words. */
    public record Interval(String fromWordId, String toWordId, long deltaNs, Kind kind, boolean parityTrusted) {}

    /** A candidate segment; candidates for alternative rates stay attached. */
    public record Segment(String channel, String startWordId, String endWordId,
                          Kind kind, double rateHz, List<Double> candidateRates,
                          int wordCount, long meanIntervalNs) {}

    public static Kind classify(long deltaNs) {
        if (deltaNs > GAP_THRESHOLD_NS) return Kind.GAP;
        if (within(deltaNs, HIGH_PERIOD_NS, 0.35)) return Kind.HIGH;
        if (within(deltaNs, LOW_PERIOD_NS, 0.35)) return Kind.LOW;
        return Kind.UNKNOWN;
    }

    private static boolean within(long v, long target, double tol) {
        return Math.abs(v - target) <= target * tol;
    }

    /** Intervals between adjacent words; bad-parity neighbours are flagged untrusted. */
    public static List<Interval> intervals(List<WordView> words) {
        List<Interval> out = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            WordView a = words.get(i - 1);
            WordView b = words.get(i);
            long delta = b.rxTimeNs() - a.rxTimeNs();
            boolean trusted = a.parityOk() && b.parityOk();
            out.add(new Interval(a.id(), b.id(), delta, classify(delta), trusted));
        }
        return out;
    }

    /**
     * Build candidate segments. Boundaries occur where the interval kind
     * changes (rate switch) or a gap appears. Untrusted (bad-parity) intervals
     * are kept visible but excluded from the mean-interval statistics.
     */
    public static List<Segment> segments(String channel, List<WordView> words) {
        List<Segment> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        List<Interval> iv = intervals(words);
        String segStart = words.get(0).id();
        Kind cur = iv.isEmpty() ? Kind.UNKNOWN : effective(iv.get(0).kind);
        List<Long> trusted = new ArrayList<>();
        int count = 1;
        for (int i = 0; i < iv.size(); i++) {
            Interval in = iv.get(i);
            Kind k = effective(in.kind);
            if (k != cur || in.kind == Kind.GAP) {
                out.add(make(channel, segStart, in.fromWordId, cur, count, trusted));
                segStart = in.toWordId;
                cur = k == Kind.GAP ? Kind.UNKNOWN : k;
                trusted = new ArrayList<>();
                count = 1;
            } else {
                count++;
                if (in.parityTrusted) trusted.add(in.deltaNs);
            }
        }
        out.add(make(channel, segStart, words.get(words.size() - 1).id, cur, count, trusted));
        return out;
    }

    private static Kind effective(Kind k) {
        return k == Kind.GAP ? Kind.UNKNOWN : k;
    }

    private static Segment make(String channel, String start, String end, Kind kind,
                                int count, List<Long> trusted) {
        long mean = trusted.isEmpty() ? 0L
                : trusted.stream().mapToLong(Long::longValue).sum() / trusted.size();
        double rate = switch (kind) {
            case LOW -> LOW_HZ;
            case HIGH -> HIGH_HZ;
            default -> 0.0;
        };
        // All legal candidates stay attached until a ruling picks one.
        List<Double> candidates = new ArrayList<>();
        if (kind == Kind.LOW || kind == Kind.UNKNOWN) candidates.add(LOW_HZ);
        if (kind == Kind.HIGH || kind == Kind.UNKNOWN) candidates.add(HIGH_HZ);
        return new Segment(channel, start, end, kind, rate, candidates, count, mean);
    }

    /** Minimal word view for segmentation. */
    public record WordView(String id, long rxTimeNs, boolean parityOk) {}
}
