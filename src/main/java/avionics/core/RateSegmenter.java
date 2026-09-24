package avionics.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 链路速率分段。速率变化与采集缺口只产生“分段候选”，
 * 无法唯一决定时保留全部合法候选，等待人工裁定；导入时不做不可逆清洗。
 */
public final class RateSegmenter {

    public record WordPoint(String wordId, int seq, long rxTimeUs) {}
    public record Segment(String candidateGroup, int startSeq, int endSeq,
                          double rateHz, String reason, List<String> wordIds) {}

    public static final double GAP_FACTOR = 2.0;
    public static final double RATE_TOLERANCE = 0.35;

    private RateSegmenter() {}

    /** 返回多个候选分组：GAP_ONLY 与 GAP_AND_RATE。 */
    public static List<Segment> segment(List<WordPoint> pts) {
        List<Segment> out = new ArrayList<>();
        if (pts.size() < 2) {
            if (!pts.isEmpty()) {
                WordPoint p = pts.get(0);
                out.add(new Segment("GAP_ONLY", p.seq(), p.seq(), 0.0, "BASELINE", List.of(p.wordId())));
            }
            return out;
        }
        long[] intervals = new long[pts.size() - 1];
        for (int i = 0; i < intervals.length; i++)
            intervals[i] = pts.get(i + 1).rxTimeUs() - pts.get(i).rxTimeUs();
        long median = median(intervals);

        boolean[] gapCut = new boolean[intervals.length];
        boolean[] rateCut = new boolean[intervals.length];
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i] > GAP_FACTOR * median) gapCut[i] = true;
            // 持续速率切换：本间隔与下一间隔同向偏离中位数
            boolean dev = Math.abs(intervals[i] - median) > RATE_TOLERANCE * median;
            boolean nextDev = i + 1 < intervals.length
                    && Math.abs(intervals[i + 1] - median) > RATE_TOLERANCE * median
                    && Math.signum(intervals[i + 1] - median) == Math.signum(intervals[i] - median);
            if (dev && nextDev && !gapCut[i]) rateCut[i] = true;
        }
        build(pts, intervals, gapCut, "GAP_ONLY", "ACQ_GAP", out);
        boolean[] both = new boolean[intervals.length];
        for (int i = 0; i < both.length; i++) both[i] = gapCut[i] || rateCut[i];
        build(pts, intervals, both, "GAP_AND_RATE", "RATE_SWITCH", out);
        return out;
    }

    private static void build(List<WordPoint> pts, long[] intervals, boolean[] cut,
                              String group, String reason, List<Segment> out) {
        int start = 0;
        for (int i = 0; i <= intervals.length; i++) {
            if (i == intervals.length || cut[i]) {
                List<String> ids = new ArrayList<>();
                for (int k = start; k <= i; k++) ids.add(pts.get(k).wordId());
                double rate = 0.0;
                if (i > start) {
                    long span = pts.get(i).rxTimeUs() - pts.get(start).rxTimeUs();
                    rate = span > 0 ? (i - start) * 1_000_000.0 / span : 0.0;
                }
                String r = (i == intervals.length && start == 0) ? "BASELINE"
                        : (i < intervals.length && cut[i]) ? reason : "BASELINE";
                out.add(new Segment(group, pts.get(start).seq(), pts.get(i).seq(), rate, r, ids));
                start = i + 1;
            }
        }
    }

    private static long median(long[] a) {
        long[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}
