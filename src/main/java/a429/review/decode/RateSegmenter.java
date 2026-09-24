package a429.review.decode;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives rate segments per (channel, label) stream from reception timestamps.
 *
 * A boundary is a gap (interval &gt; gapFactor x previous interval) or a rate
 * switch (interval change beyond tolerance). Every segment keeps ALL legal
 * rate candidates: the observed rate plus every configured nominal rate whose
 * distance from observed is within the tolerance. Nothing is collapsed to a
 * single answer until a human adjudicates.
 */
public final class RateSegmenter {

    public record Point(String wordUid, long timeNs, boolean aggregateEligible, String excludeReason) {
    }

    public record Interval(String fromWordUid, String toWordUid,
                           long dtNs, double dtMs, String kind) {
    }

    public record RateCandidate(double bitPerSecond, String source, double deviationPct, boolean nominal) {
    }

    public record Segment(int index, String channel, String octalLabel,
                          String startWordUid, String endWordUid,
                          long startNs, long endNs,
                          int wordCount, int excludedCount,
                          List<RateCandidate> candidates,
                          List<String> excludedWordUids) {
    }

    public record Result(List<Segment> segments, List<Interval> intervals,
                         List<Point> excluded) {
    }

    private final List<Double> nominalRates;
    private final double tolerancePct;
    private final double gapFactor;

    public RateSegmenter(List<Double> nominalRates, double tolerancePct, double gapFactor) {
        this.nominalRates = List.copyOf(nominalRates);
        this.tolerancePct = tolerancePct;
        this.gapFactor = gapFactor;
    }

    public Result segment(String channel, String octLabel, List<Point> points) {
        List<Interval> intervals = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        List<Point> excluded = new ArrayList<>();
        if (points.isEmpty()) {
            return new Result(segments, intervals, excluded);
        }
        // Boundaries derived from raw intervals regardless of eligibility
        int segStart = 0;
        for (int i = 1; i < points.size(); i++) {
            long dt = points.get(i).timeNs - points.get(i - 1).timeNs;
            long prevDt = i >= 2 ? points.get(i - 1).timeNs - points.get(i - 2).timeNs : dt;
            String kind = "SAME";
            if (prevDt > 0 && dt > gapFactor * prevDt) {
                kind = "GAP";
            } else if (prevDt > 0) {
                double change = Math.abs(dt - prevDt) * 100.0 / prevDt;
                if (change > tolerancePct) {
                    kind = "RATE_SWITCH";
                }
            }
            intervals.add(new Interval(points.get(i - 1).wordUid, points.get(i).wordUid,
                    dt, dt / 1e6, kind));
            if (!"SAME".equals(kind)) {
                segments.add(build(segments.size(), channel, octLabel, points, segStart, i - 1, intervals));
                segStart = i;
            }
        }
        segments.add(build(segments.size(), channel, octLabel, points, segStart, points.size() - 1, intervals));

        for (Point p : points) {
            if (!p.aggregateEligible) {
                excluded.add(p);
            }
        }
        return new Result(segments, intervals, excluded);
    }

    private Segment build(int idx, String channel, String octLabel, List<Point> points,
                          int lo, int hi, List<Interval> intervals) {
        List<Point> segPts = points.subList(lo, hi + 1);
        long total = segPts.get(segPts.size() - 1).timeNs - segPts.get(0).timeNs;
        double observedBps = segPts.size() < 2 ? Double.NaN
                : 32.0 * (segPts.size() - 1) / (total / 1_000_000_000.0);

        List<RateCandidate> cands = new ArrayList<>();
        cands.add(new RateCandidate(round(observedBps), "OBSERVED", 0.0, false));
        if (!Double.isNaN(observedBps)) {
            for (double nominal : nominalRates) {
                double dev = Math.abs(nominal - observedBps) * 100.0 / nominal;
                if (dev <= tolerancePct) {
                    cands.add(new RateCandidate(round(nominal), "NOMINAL", round(dev), true));
                }
            }
        }
        List<String> excludedUids = new ArrayList<>();
        int excludedCount = 0;
        for (Point p : segPts) {
            if (!p.aggregateEligible) {
                excludedCount++;
                excludedUids.add(p.wordUid);
            }
        }
        return new Segment(idx, channel, octLabel,
                segPts.get(0).wordUid, segPts.get(segPts.size() - 1).wordUid,
                segPts.get(0).timeNs, segPts.get(segPts.size() - 1).timeNs,
                segPts.size(), excludedCount, List.copyOf(cands), List.copyOf(excludedUids));
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
