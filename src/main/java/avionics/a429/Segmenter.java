package avionics.a429;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Link-rate segmentation. Rate changes and acquisition gaps never collapse to a
 * single answer at import time: every boundary keeps all legitimate candidates
 * until a human ruling picks one.
 */
public final class Segmenter {

    public static final class Point {
        public final String wordId;
        public final int seq;
        public final Instant rxTime;

        public Point(String wordId, int seq, Instant rxTime) {
            this.wordId = wordId;
            this.seq = seq;
            this.rxTime = rxTime;
        }
    }

    public static final class Candidate {
        public final String segmentId;
        public final String candidateId;
        public final String kind;    // rate-switch | gap | jitter
        public final String detail;

        Candidate(String segmentId, String candidateId, String kind, String detail) {
            this.segmentId = segmentId;
            this.candidateId = candidateId;
            this.kind = kind;
            this.detail = detail;
        }
    }

    /** Analyze one (channel, label) series ordered by in-situ sequence. */
    public static List<Candidate> analyze(String channel, String labelOctal, List<Point> points) {
        List<Candidate> out = new ArrayList<>();
        if (points.size() < 3) {
            return out;
        }
        long[] intervals = new long[points.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = points.get(i + 1).rxTime.toEpochMilli() - points.get(i).rxTime.toEpochMilli();
        }
        long nominal = median(intervals);
        if (nominal <= 0) {
            return out;
        }
        int n = 0;
        for (int i = 0; i < intervals.length; i++) {
            long dt = intervals[i];
            double ratio = (double) dt / nominal;
            String boundaryWord = points.get(i + 1).wordId;
            String segId = "seg-" + channel + "-" + labelOctal + "-" + points.get(i + 1).seq;
            if (ratio > 3.0) {
                n++;
                out.add(new Candidate(segId, segId + "-gap", "gap",
                        "采集缺口: 相邻字间隔 " + dt + " ms = " + String.format("%.1f", ratio)
                                + "x 标称 " + nominal + " ms (边界字 " + boundaryWord + "), 建议分段"));
                out.add(new Candidate(segId, segId + "-jitter", "jitter",
                        "保持单段: 间隔 " + dt + " ms 可能为调度抖动, 不拆分"));
            } else if (ratio > 1.3 || ratio < 0.7) {
                boolean sustained = i + 1 < intervals.length
                        && Math.abs(intervals[i + 1] - dt) <= 0.4 * dt;
                if (sustained) {
                    n++;
                    out.add(new Candidate(segId, segId + "-rate", "rate-switch",
                            "速率切换: 标称 " + nominal + " ms -> 稳定 " + dt + " ms (边界字 "
                                    + boundaryWord + "), 建议分段"));
                    out.add(new Candidate(segId, segId + "-gap", "gap",
                            "采集缺口: 间隔 " + dt + " ms 后恢复, 按缺口分段"));
                    out.add(new Candidate(segId, segId + "-jitter", "jitter",
                            "保持单段: 速率变化不显著, 不拆分"));
                }
            }
        }
        return out;
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }
}
