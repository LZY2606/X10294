package org.avionics.review.codecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.avionics.review.domain.ChannelConfig;
import org.avionics.review.domain.DecodeView;
import org.avionics.review.domain.Interval;
import org.avionics.review.domain.RateCandidate;
import org.avionics.review.domain.RateParams;
import org.avionics.review.domain.RateSegment;
import org.avionics.review.domain.SourceWord;

/**
 * Splits a channel's in-place word stream at acquisition gaps and keeps every link rate
 * (100 kbit/s / 12.5 kbit/s) that is consistent with the observed gaps. Nothing is collapsed
 * to a single answer until a human ruling selects one candidate.
 */
public final class RateSegmenter {

    private RateSegmenter() {
    }

    public record Result(List<Interval> intervals, List<RateSegment> segments) {
    }

    public static Result analyze(List<SourceWord> words, ChannelConfig channel, RateParams params) {
        List<Interval> intervals = new ArrayList<>();
        List<RateSegment> segments = new ArrayList<>();
        if (words.isEmpty()) {
            return new Result(intervals, segments);
        }

        List<int[]> boundaries = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            long gapNanos = words.get(i).receivedNanos() - words.get(i - 1).receivedNanos();
            boolean acqGap = gapNanos / 1000.0 >= params.gapThresholdMicros();
            intervals.add(new Interval(words.get(i - 1).wordId(), words.get(i).wordId(),
                    gapNanos, acqGap));
            if (acqGap) {
                boundaries.add(new int[]{i - 1, i});
            }
        }

        int segStart = 0;
        int segIndex = 0;
        for (int[] b : boundaries) {
            addSegment(segments, segIndex++, words, segStart, b[0], channel, params);
            segStart = b[1];
        }
        addSegment(segments, segIndex, words, segStart, words.size() - 1, channel, params);
        return new Result(intervals, segments);
    }

    private static void addSegment(List<RateSegment> out, int index, List<SourceWord> words,
                                   int from, int to, ChannelConfig channel, RateParams params) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            ids.add(words.get(i).wordId());
        }
        double minGapMicros = Double.POSITIVE_INFINITY;
        for (int i = from + 1; i <= to; i++) {
            minGapMicros = Math.min(minGapMicros,
                    (words.get(i).receivedNanos() - words.get(i - 1).receivedNanos()) / 1000.0);
        }
        List<RateCandidate> candidates = new ArrayList<>();
        if (ids.size() < 2) {
            out.add(new RateSegment(channel.id(), index, ids.get(0), ids.get(ids.size() - 1),
                    ids, List.of(), "single-word segment: no inter-word gap, rate undecidable"));
            return;
        }
        candidates.add(matchRate(minGapMicros, params.highRateWordMicros(),
                "100 kbit/s", params.toleranceFraction()));
        candidates.add(matchRate(minGapMicros, params.lowRateWordMicros(),
                "12.5 kbit/s", params.toleranceFraction()));
        candidates.removeIf(c -> c == null);

        String ambiguity;
        if (candidates.size() > 1) {
            ambiguity = "ambiguous: " + candidates.size()
                    + " legal rates fit the minimum gap; human ruling required";
        } else if (candidates.isEmpty()) {
            ambiguity = "no rate candidate fits within tolerance";
        } else {
            ambiguity = "unique";
        }
        out.add(new RateSegment(channel.id(), index, ids.get(0), ids.get(ids.size() - 1),
                ids, candidates, ambiguity));
    }

    private static RateCandidate matchRate(double minGapMicros, double periodMicros,
                                           String linkRate, double tolerance) {
        double ratio = minGapMicros / periodMicros;
        long n = Math.max(1, Math.round(ratio));
        double expected = n * periodMicros;
        double residual = Math.abs(expected - minGapMicros) / minGapMicros;
        if (residual <= tolerance) {
            return new RateCandidate(linkRate, periodMicros, minGapMicros, residual,
                    "min gap " + String.format(java.util.Locale.ROOT, "%.1f", minGapMicros)
                            + " us ~= " + n + " x " + String.format(java.util.Locale.ROOT, "%.0f", periodMicros)
                            + " us word time, residual " + String.format(java.util.Locale.ROOT, "%.3f", residual));
        }
        return null;
    }

    /** Aggregate engineering values per label: bad parity / invalid SSM words never enter. */
    public static Map<String, AggregateStats> aggregate(List<SourceWord> words,
                                                        Map<String, DecodeView> decodeByWord) {
        java.util.LinkedHashMap<String, AggregateStats> out = new java.util.LinkedHashMap<>();
        for (SourceWord w : words) {
            DecodeView view = decodeByWord.get(w.wordId());
            if (view == null || view.adopted() == null) {
                continue;
            }
            if (!view.adopted().usableForAggregate()) {
                continue;
            }
            Double v = view.adopted().engineering().value();
            if (v == null) {
                continue;
            }
            String key = view.adopted().label() + "@" + view.adopted().dictVersion();
            out.computeIfAbsent(key, k -> new AggregateStats(k, view.adopted().engineering().unit()))
                    .add(v, w.wordId());
        }
        return out;
    }

    public static class AggregateStats {
        public final String key;
        public final String unit;
        public final List<Double> values = new ArrayList<>();
        public final List<String> contributingWordIds = new ArrayList<>();

        public AggregateStats(String key, String unit) {
            this.key = key;
            this.unit = unit;
        }

        void add(double v, String wordId) {
            values.add(v);
            contributingWordIds.add(wordId);
        }

        public double mean() {
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        }
    }
}
