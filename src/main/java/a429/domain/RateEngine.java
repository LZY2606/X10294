package a429.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Rate segmentation. Standard ARINC 429 speeds are 12.5 kbit/s
 * (32 bits/word =&gt; 2.56 ms word period) and 100 kbit/s (0.32 ms).
 * Every speed compatible with an interval becomes a candidate; ambiguity is
 * preserved rather than guessed away.
 */
public class RateEngine {

    public static final List<Integer> STANDARD_SPEEDS = List.of(12500, 100000);

    public record Params(double gapThresholdMs, double toleranceFraction, List<Integer> speedsBps) {
        public static Params standard(double gapThresholdMs, double toleranceFraction) {
            return new Params(gapThresholdMs, toleranceFraction, STANDARD_SPEEDS);
        }
    }

    public record ChannelResult(String channelId, List<Interval> intervals,
                                List<RateSegment> segments) {
    }

    public ChannelResult analyse(String channelId, List<RawWord> channelWords,
                                 Params params, String idPrefix) {
        List<RawWord> sorted = channelWords.stream()
                .sorted(java.util.Comparator.comparingLong(RawWord::seq)).toList();
        if (sorted.isEmpty()) {
            return new ChannelResult(channelId, List.of(), List.of());
        }

        List<Interval> intervals = buildIntervals(sorted, params, idPrefix);
        List<RateSegment> segments = buildSegments(sorted, intervals, channelId, idPrefix);
        return new ChannelResult(channelId, intervals, segments);
    }

    private List<Interval> buildIntervals(List<RawWord> sorted, Params params, String idPrefix) {
        List<Interval> intervals = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            RawWord prev = sorted.get(i - 1);
            RawWord cur = sorted.get(i);
            String iid = idPrefix + "-iv-" + (i - 1);
            if (prev.receiveEpochNanos() == null || cur.receiveEpochNanos() == null) {
                intervals.add(new Interval(iid, prev.wordId(), cur.wordId(), prev.seq(), cur.seq(),
                        Double.NaN, List.of(), List.of(), false,
                        List.of("timestamp-not-parsed: original receive text retained, interval not timed")));
                continue;
            }
            double deltaMs = (cur.receiveEpochNanos() - prev.receiveEpochNanos()) / 1_000_000.0;
            if (deltaMs > params.gapThresholdMs()) {
                intervals.add(new Interval(iid, prev.wordId(), cur.wordId(), prev.seq(), cur.seq(),
                        round3(deltaMs), List.of(), List.of(), true,
                        List.of("ACQUISITION_GAP: delta " + round3(deltaMs)
                                + " ms exceeds gap threshold " + params.gapThresholdMs() + " ms")));
                continue;
            }
            List<Integer> speeds = new ArrayList<>();
            List<Integer> multiples = new ArrayList<>();
            for (int speed : params.speedsBps()) {
                double wordPeriodMs = 32.0 / speed * 1000.0;
                double ratio = deltaMs / wordPeriodMs;
                long mult = Math.round(ratio);
                if (mult >= 1 && Math.abs(ratio - mult) / mult <= params.toleranceFraction()) {
                    speeds.add(speed);
                    multiples.add((int) mult);
                }
            }
            List<String> notes = new ArrayList<>();
            if (speeds.isEmpty()) {
                notes.add("NO_RATE_CANDIDATE: " + round3(deltaMs)
                        + " ms matches no standard word period within tolerance "
                        + (params.toleranceFraction() * 100) + "%");
            }
            intervals.add(new Interval(iid, prev.wordId(), cur.wordId(), prev.seq(), cur.seq(),
                    round3(deltaMs), List.copyOf(speeds), List.copyOf(multiples), false, notes));
        }
        return intervals;
    }

    private List<RateSegment> buildSegments(List<RawWord> sorted, List<Interval> intervals,
                                            String channelId, String idPrefix) {
        List<RateSegment> segments = new ArrayList<>();
        int segStartWord = 0;
        List<Integer> segIntervalIndexes = new ArrayList<>();
        List<Integer> currentSpeeds = null;
        double intervalSum = 0;

        for (int k = 0; k < intervals.size(); k++) {
            Interval iv = intervals.get(k);
            boolean breaker = iv.acquisitionGap() || Double.isNaN(iv.deltaMs())
                    || iv.compatibleSpeedsBps().isEmpty();

            if (breaker) {
                if (!segIntervalIndexes.isEmpty()) {
                    segments.add(buildTrafficSegment(sorted, segStartWord, segIntervalIndexes,
                            currentSpeeds, intervalSum, channelId, idPrefix, segments.size()));
                }
                String kind = iv.acquisitionGap() ? "GAP"
                        : (Double.isNaN(iv.deltaMs()) ? "UNTIMED" : "UNRESOLVED");
                segments.add(new RateSegment(idPrefix + "-seg-" + segments.size(), channelId,
                        segments.size(), kind, sorted.get(k).seq(), sorted.get(k + 1).seq(),
                        timeMs(sorted.get(k)), timeMs(sorted.get(k + 1)), iv.deltaMs(),
                        List.of(), false, List.of(iv.intervalId()), new ArrayList<>(iv.notes())));
                segStartWord = k + 1;
                segIntervalIndexes = new ArrayList<>();
                currentSpeeds = null;
                intervalSum = 0;
                continue;
            }

            if (currentSpeeds == null) {
                currentSpeeds = iv.compatibleSpeedsBps();
            } else if (!currentSpeeds.equals(iv.compatibleSpeedsBps())) {
                segments.add(buildTrafficSegment(sorted, segStartWord, segIntervalIndexes,
                        currentSpeeds, intervalSum, channelId, idPrefix, segments.size()));
                segStartWord = k;
                segIntervalIndexes = new ArrayList<>();
                intervalSum = 0;
                currentSpeeds = iv.compatibleSpeedsBps();
            }
            segIntervalIndexes.add(k);
            intervalSum += iv.deltaMs();
        }
        if (!segIntervalIndexes.isEmpty()) {
            segments.add(buildTrafficSegment(sorted, segStartWord, segIntervalIndexes,
                    currentSpeeds, intervalSum, channelId, idPrefix, segments.size()));
        }
        if (segments.isEmpty()) {
            segments.add(new RateSegment(idPrefix + "-seg-0", channelId, 0, "SINGLE_WORD",
                    sorted.get(0).seq(), sorted.get(0).seq(), timeMs(sorted.get(0)),
                    timeMs(sorted.get(0)), 0, List.of(), false, List.of(),
                    List.of("single word on channel, no adjacent interval")));
        }
        return segments;
    }

    private RateSegment buildTrafficSegment(List<RawWord> sorted, int startWord,
                                            List<Integer> segIntervalIndexes, List<Integer> speeds,
                                            double intervalSum, String channelId, String idPrefix,
                                            int ordinal) {
        int firstIv = segIntervalIndexes.get(0);
        int lastIv = segIntervalIndexes.get(segIntervalIndexes.size() - 1);
        List<String> ids = new ArrayList<>();
        for (int idx : segIntervalIndexes) {
            ids.add(idPrefix + "-iv-" + idx);
        }
        RawWord start = sorted.get(firstIv);
        RawWord end = sorted.get(lastIv + 1);
        List<String> notes = new ArrayList<>();
        if (speeds.size() > 1) {
            notes.add("AMBIGUOUS: " + speeds.size()
                    + " legal speed candidates retained, human adjudication required");
        }
        return new RateSegment(idPrefix + "-seg-" + ordinal, channelId, ordinal, "TRAFFIC",
                start.seq(), end.seq(), timeMs(start), timeMs(end),
                round3(intervalSum / segIntervalIndexes.size()), List.copyOf(speeds),
                speeds.size() == 1, List.copyOf(ids), notes);
    }

    private double timeMs(RawWord w) {
        return w.receiveEpochNanos() == null ? Double.NaN
                : round3(w.receiveEpochNanos() / 1_000_000.0);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
