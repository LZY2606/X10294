package a429.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Link-rate segmentation over in-situ word order.
 *
 * Rate changes and acquisition gaps are never resolved silently: every
 * suspicious interval becomes a boundary candidate carrying all legitimate
 * hypotheses until a reviewer adjudicates it.
 */
public final class RateSegmenter {

    /** A received word positioned in time and sequence. */
    public record WordPoint(String wordId, int seq, long rxTimeNs, String channel, String labelOctal) {}

    /**
     * A boundary candidate between two adjacent words.
     * hypotheses: subset of RATE_SWITCH / ACQUISITION_GAP, both kept when ambiguous.
     */
    public record BoundaryCandidate(
            String id,
            int afterSeq,
            int beforeSeq,
            long intervalNs,
            double medianIntervalNs,
            List<String> hypotheses) {}

    /** A contiguous run of words at one candidate rate. */
    public record Segment(String id, int startSeq, int endSeq, double rateHz, int wordCount) {}

    public record Segmentation(
            double medianIntervalNs,
            double baseRateHz,
            List<BoundaryCandidate> boundaries,
            List<Segment> segments) {}

    public static final String RATE_SWITCH = "RATE_SWITCH";
    public static final String ACQUISITION_GAP = "ACQUISITION_GAP";

    /**
     * @param points    words of one channel+label stream, already in in-situ order
     * @param tolerance fractional deviation from the median interval that raises a boundary
     * @param gapFactor interval longer than gapFactor * median is a gap hypothesis
     */
    public static Segmentation segment(List<WordPoint> points, double tolerance, double gapFactor) {
        List<BoundaryCandidate> boundaries = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        if (points.size() < 2) {
            double rate = points.isEmpty() ? 0 : Double.NaN;
            if (!points.isEmpty()) {
                segments.add(new Segment("seg-" + points.get(0).seq() + "-" + points.get(0).seq(),
                        points.get(0).seq(), points.get(0).seq(), rate, 1));
            }
            return new Segmentation(0, rate, boundaries, segments);
        }
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            intervals.add(points.get(i).rxTimeNs() - points.get(i - 1).rxTimeNs());
        }
        List<Long> sorted = new ArrayList<>(intervals);
        sorted.sort(Long::compareTo);
        double median = sorted.get(sorted.size() / 2);

        int segStart = points.get(0).seq();
        int segCount = 1;
        long segIntervalSum = 0;
        int segIntervalCount = 0;
        int boundaryIndex = 0;
        for (int i = 1; i < points.size(); i++) {
            long interval = intervals.get(i - 1);
            double deviation = median == 0 ? 0 : Math.abs(interval - median) / median;
            if (deviation > tolerance) {
                List<String> hypotheses = new ArrayList<>();
                if (interval > median * gapFactor) {
                    hypotheses.add(ACQUISITION_GAP);
                    hypotheses.add(RATE_SWITCH);
                } else {
                    hypotheses.add(RATE_SWITCH);
                    hypotheses.add(ACQUISITION_GAP);
                }
                boundaries.add(new BoundaryCandidate(
                        "bnd-" + points.get(i - 1).seq() + "-" + points.get(i).seq(),
                        points.get(i - 1).seq(), points.get(i).seq(), interval, median, hypotheses));
                segments.add(new Segment("seg-" + segStart + "-" + points.get(i - 1).seq(),
                        segStart, points.get(i - 1).seq(), rate(segIntervalSum, segIntervalCount), segCount));
                segStart = points.get(i).seq();
                segCount = 0;
                segIntervalSum = 0;
                segIntervalCount = 0;
                boundaryIndex++;
            } else {
                segIntervalSum += interval;
                segIntervalCount++;
            }
            segCount++;
        }
        segments.add(new Segment("seg-" + segStart + "-" + points.get(points.size() - 1).seq(),
                segStart, points.get(points.size() - 1).seq(), rate(segIntervalSum, segIntervalCount), segCount));
        double baseRate = median == 0 ? Double.NaN : 1_000_000_000.0 / median;
        return new Segmentation(median, baseRate, boundaries, segments);
    }

    private static double rate(long intervalSumNs, int count) {
        if (count == 0 || intervalSumNs == 0) {
            return Double.NaN;
        }
        return 1_000_000_000.0 / ((double) intervalSumNs / count);
    }
}
