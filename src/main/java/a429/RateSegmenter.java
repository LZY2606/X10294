package a429;

import java.util.ArrayList;
import java.util.List;

/** Rate segmentation from adjacent-word intervals. Rate changes and acquisition gaps
 *  keep segment candidates; no candidate is dropped at import time. */
public final class RateSegmenter {

    public record WordTiming(String wordId, int seq, long rxTimeNs) {}
    public record Segment(String id, int startSeq, int endSeq, double wordRateHz,
                          double gapBeforeMs, boolean acquisitionGap, List<String> linkSpeedCandidates) {}

    /** A gap larger than GAP_FACTOR x the local interval opens a new segment;
     *  a gap larger than ACQ_GAP_MS is additionally flagged as an acquisition gap. */
    public static List<Segment> segment(List<WordTiming> words) {
        List<Segment> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        double medianGapMs = medianGapMs(words);
        double threshold = Math.max(medianGapMs * 3.0, 1.0);
        int segStart = 0;
        int idx = 1;
        for (int i = 1; i <= words.size(); i++) {
            double gapMs = i < words.size() ? (words.get(i).rxTimeNs() - words.get(i - 1).rxTimeNs()) / 1e6 : -1;
            boolean boundary = i == words.size() || gapMs > threshold;
            if (boundary) {
                WordTiming first = words.get(segStart);
                WordTiming last = words.get(i - 1);
                double spanMs = (last.rxTimeNs() - first.rxTimeNs()) / 1e6;
                int n = i - segStart;
                double rate = n > 1 && spanMs > 0 ? (n - 1) * 1000.0 / spanMs : 0;
                double gapBefore = segStart == 0 ? 0 : (first.rxTimeNs() - words.get(segStart - 1).rxTimeNs()) / 1e6;
                boolean acqGap = gapBefore > 200.0;
                // Both ARINC link speeds stay as candidates: word timing alone cannot disambiguate them.
                out.add(new Segment("seg-" + idx++, first.seq(), last.seq(), round(rate), round(gapBefore),
                        acqGap, List.of("LOW_12_5_KBPS", "HIGH_100_KBPS")));
                segStart = i;
            }
        }
        return out;
    }

    private static double medianGapMs(List<WordTiming> words) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            double g = (words.get(i).rxTimeNs() - words.get(i - 1).rxTimeNs()) / 1e6;
            if (g > 0) gaps.add(g);
        }
        if (gaps.isEmpty()) return 50.0;
        gaps.sort(Double::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
