package a429;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Segmenter {

    public record Interval(String fromUid, String toUid, long deltaNs, double ms) {
    }

    public record Segment(String id, String channel, String kind, String startUid, String endUid,
                          Double rateMs, List<String> candidates, long firstNs, long lastNs,
                          int wordCount) {
    }

    public record Output(List<Segment> segments, List<Interval> intervals) {
    }

    private Segmenter() {
    }

    public static Output segment(String prefix, List<WordRow> allWords, String channel) {
        List<WordRow> words = allWords.stream()
                .filter(w -> w.channel().equals(channel))
                .sorted(Comparator.comparingInt(WordRow::seq))
                .toList();
        List<Interval> intervals = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            long delta = words.get(i).rxTimeNs() - words.get(i - 1).rxTimeNs();
            intervals.add(new Interval(words.get(i - 1).uid(), words.get(i).uid(),
                    delta, delta / 1_000_000.0));
        }
        double median = median(intervals.stream().map(Interval::ms).toList());
        double threshold = 2.5 * median;

        List<Segment> result = new ArrayList<>();
        int start = 0;
        int segIndex = 1;
        int boundaryIndex = 1;
        for (int i = 1; i < words.size(); i++) {
            double gap = words.get(i).rxTimeNs() - words.get(i - 1).rxTimeNs();
            if (gap > threshold * 1_000_000L) {
                result.add(buildSegment(prefix + "-seg" + segIndex, words, channel, start, i - 1));
                segIndex++;
                List<String> candidates = new ArrayList<>();
                candidates.add("ACQUISITION_GAP");
                candidates.add("RATE_SWITCH");
                result.add(new Segment(prefix + "-bnd" + boundaryIndex, channel, "BOUNDARY",
                        words.get(i - 1).uid(), words.get(i).uid(),
                        gap / 1_000_000.0, candidates,
                        words.get(i - 1).rxTimeNs(), words.get(i).rxTimeNs(), 2));
                boundaryIndex++;
                start = i;
            }
        }
        result.add(buildSegment(prefix + "-seg" + segIndex, words, channel,
                start, words.size() - 1));
        return new Output(result, intervals);
    }

    private static Segment buildSegment(String id, List<WordRow> words, String channel,
                                        int start, int endInclusive) {
        long first = words.get(start).rxTimeNs();
        long last = words.get(endInclusive).rxTimeNs();
        int count = endInclusive - start + 1;
        Double rate = count > 1 ? ((last - first) / 1_000_000.0) / (count - 1) : null;
        return new Segment(id, channel, "SEGMENT", words.get(start).uid(),
                words.get(endInclusive).uid(), rate, List.of(), first, last, count);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
