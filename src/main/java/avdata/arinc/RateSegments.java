package avdata.arinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Link-rate segmentation from receive timestamps.
 *
 * <p>ARINC 429 has two nominal line rates: 100 kbit/s ("high", 10 ms word slot) and
 * 12..14.5 kbit/s ("low", ~78 ms word slot at 12.5 kbit/s). Captured streams often schedule
 * words at multiples of the basic slot. Rather than forcing a single answer, every interval
 * is compared to both rates with tolerance, and all legal segment-rate candidates are kept
 * when the data cannot uniquely decide (e.g. 40 ms fits both 4x high slots and a relaxed low
 * schedule). Acquisition gaps that exceed a configurable multiplier break segments but are
 * never silently smoothed over.
 */
public final class RateSegments {

    public static final double HIGH_KBPS = 100.0;
    public static final double LOW_KBPS = 12.5;
    public static final double HIGH_SLOT_MS = 10.0;
    public static final double LOW_SLOT_MS = 32.0 / LOW_KBPS; // 32 bits / 12.5 kbps = 2.56 ms...

    static {
        // actual 32-bit word time on the wire at 12.5 kbps is 2.56 ms; the conventional
        // ARINC "low speed word interval" used for scheduling is ~40..80 ms. We model
        // scheduler slots rather than wire time for interval matching.
    }

    /** Nominal scheduler slot per rate in milliseconds. */
    public static final double HIGH_SCHEDULE_MS = 10.0;
    public static final double LOW_SCHEDULE_MS = 80.0;

    /** Relative tolerance for matching a multiple of a slot. */
    public static final double TOLERANCE = 0.25;
    /** Gap beyond gapFactor consecutive low-rate slots breaks a segment. */
    public static final double GAP_FACTOR = 3.0;

    private RateSegments() {
    }

    public static final class Interval {
        public final String uid;
        public final int fromSeq;
        public final int toSeq;
        public final double deltaMs;
        public final boolean acquisitionGap;

        Interval(String uid, int fromSeq, int toSeq, double deltaMs, boolean gap) {
            this.uid = uid;
            this.fromSeq = fromSeq;
            this.toSeq = toSeq;
            this.deltaMs = deltaMs;
            this.acquisitionGap = gap;
        }
    }

    public static final class RateCandidate {
        public final String rateKey;        // "HIGH" / "LOW"
        public final double nominalKbps;
        public final int slotMultiple;      // interval / schedule slot
        public final double expectedMs;

        RateCandidate(String rateKey, double nominalKbps, int slotMultiple, double expectedMs) {
            this.rateKey = rateKey;
            this.nominalKbps = nominalKbps;
            this.slotMultiple = slotMultiple;
            this.expectedMs = expectedMs;
        }
    }

    public static final class Segment {
        public final String uid;
        public final int startSeq;
        public final int endSeq;
        public final long startEpochMs;
        public final long endEpochMs;
        public final List<String> rateCandidates; // rateKeys, possibly >1
        public final double medianIntervalMs;
        public final String gapReason;

        Segment(String uid, int startSeq, int endSeq, long startEpochMs, long endEpochMs,
                List<String> rateCandidates, double medianIntervalMs, String gapReason) {
            this.uid = uid;
            this.startSeq = startSeq;
            this.endSeq = endSeq;
            this.startEpochMs = startEpochMs;
            this.endEpochMs = endEpochMs;
            this.rateCandidates = List.copyOf(rateCandidates);
            this.medianIntervalMs = medianIntervalMs;
            this.gapReason = gapReason == null ? "" : gapReason;
        }
    }

    /** Legal rate candidates for one interval; ambiguity is preserved. */
    public static List<RateCandidate> candidatesFor(double deltaMs,
                                                    double highScheduleMs,
                                                    double lowScheduleMs,
                                                    double tolerance) {
        List<RateCandidate> out = new ArrayList<>();
        addCandidates(out, "HIGH", HIGH_KBPS, highScheduleMs, deltaMs, tolerance);
        addCandidates(out, "LOW", LOW_KBPS, lowScheduleMs, deltaMs, tolerance);
        return out;
    }

    private static void addCandidates(List<RateCandidate> out, String key, double kbps,
                                      double slot, double deltaMs, double tolerance) {
        if (deltaMs <= 0 || slot <= 0) {
            return;
        }
        int rounded = (int) Math.round(deltaMs / slot);
        if (rounded < 1) {
            rounded = 1;
        }
        // check nearest two multiples to be tolerant at boundaries
        for (int m = Math.max(1, rounded - 1); m <= rounded + 1; m++) {
            double expected = m * slot;
            if (within(deltaMs, expected, tolerance)) {
                RateCandidate c = new RateCandidate(key, kbps, m, expected);
                boolean dup = out.stream().anyMatch(x -> x.rateKey.equals(key) && x.slotMultiple == m);
                if (!dup) {
                    out.add(c);
                }
            }
        }
    }

    private static boolean within(double delta, double expected, double tolerance) {
        return Math.abs(delta - expected) <= expected * tolerance;
    }

    public static boolean isAcquisitionGap(double deltaMs, double lowScheduleMs) {
        return deltaMs > lowScheduleMs * GAP_FACTOR;
    }

    /**
     * Build intervals and segments for one channel's ordered words.
     *
     * @param seqs    in-place sequences
     * @param epochs  receive timestamps (epoch ms), same order
     * @param idStem stable stem used to derive deterministic UIDs
     */
    public static final class Result {
        public final List<Interval> intervals;
        public final List<Segment> segments;

        Result(List<Interval> intervals, List<Segment> segments) {
            this.intervals = List.copyOf(intervals);
            this.segments = List.copyOf(segments);
        }
    }

    public static Result segment(String idStem, List<Integer> seqs, List<Long> epochs,
                                 Double highScheduleMs, Double lowScheduleMs) {
        double high = highScheduleMs == null ? HIGH_SCHEDULE_MS : highScheduleMs;
        double low = lowScheduleMs == null ? LOW_SCHEDULE_MS : lowScheduleMs;

        List<Interval> intervals = new ArrayList<>();
        List<Boolean> gaps = new ArrayList<>();
        List<Double> deltas = new ArrayList<>();
        for (int i = 1; i < seqs.size(); i++) {
            double d = epochs.get(i) - epochs.get(i - 1);
            boolean gap = isAcquisitionGap(d, low);
            String uid = idStem + ":interval:" + seqs.get(i - 1) + "-" + seqs.get(i);
            intervals.add(new Interval(uid, seqs.get(i - 1), seqs.get(i), d, gap));
            gaps.add(gap);
            deltas.add(d);
        }

        List<Segment> segments = new ArrayList<>();
        if (!seqs.isEmpty()) {
            int segStart = 0;
            for (int i = 0; i < gaps.size(); i++) {
                if (gaps.get(i)) {
                    segments.add(buildSegment(idStem, segStart, i, seqs, epochs, deltas, high, low,
                            "acquisition gap " + String.format("%.0f", deltas.get(i)) + " ms"));
                    segStart = i + 1;
                }
            }
            segments.add(buildSegment(idStem, segStart, seqs.size() - 1, seqs, epochs, deltas,
                    high, low, ""));
        }
        return new Result(intervals, segments);
    }

    private static Segment buildSegment(String idStem, int start, int end,
                                        List<Integer> seqs, List<Long> epochs,
                                        List<Double> deltas, double high, double low,
                                        String gapReason) {
        List<Double> segDeltas = new ArrayList<>();
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (int i = start; i < end; i++) {
            double d = deltas.get(i);
            segDeltas.add(d);
            for (RateCandidate c : candidatesFor(d, high, low, TOLERANCE)) {
                votes.merge(c.rateKey, 1, Integer::sum);
            }
        }
        List<String> candidates = new ArrayList<>();
        if (!votes.isEmpty()) {
            int max = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            for (Map.Entry<String, Integer> e : votes.entrySet()) {
                if (e.getValue() == max) {
                    candidates.add(e.getKey());
                }
            }
        } else {
            candidates.add("UNKNOWN");
        }
        double median = segDeltas.isEmpty() ? 0.0 : median(segDeltas);
        String uid = idStem + ":segment:" + seqs.get(start) + "-" + seqs.get(end);
        return new Segment(uid, seqs.get(start), seqs.get(end), epochs.get(start),
                epochs.get(end), candidates, median, gapReason);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
