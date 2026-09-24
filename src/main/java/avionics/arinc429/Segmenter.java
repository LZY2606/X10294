package avionics.arinc429;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 速率分段：按通道对原位序列的相邻字间隔聚类。
 * 速率变化与采集缺口产生分段边界；无法唯一决定时保留全部合法候选。
 * 坏奇偶字不参与间隔统计（不进入聚合值）。
 */
public final class Segmenter {

    /** 间隔超过中位数的该倍数即视为采集缺口。 */
    public static final double GAP_FACTOR = 3.0;

    public record WordPoint(int seq, long rxNanos, boolean parityOk) {}

    public record RateCandidate(String id, double rateHz, double intervalMs, int votes, String status) {}

    public record Segment(
            String id,
            String channel,
            int startSeq,
            int endSeq,
            boolean gapBefore,
            List<RateCandidate> candidates
    ) {}

    /** 对单通道字序列分段，返回带候选速率的分段列表。 */
    public static List<Segment> segment(String channel, List<WordPoint> words) {
        List<Segment> out = new ArrayList<>();
        List<WordPoint> good = new ArrayList<>();
        for (WordPoint w : words) if (w.parityOk()) good.add(w);
        if (good.isEmpty()) return out;

        List<WordPoint> current = new ArrayList<>();
        List<Long> currentIntervals = new ArrayList<>();
        current.add(good.get(0));
        boolean gapBefore = false;
        int segNo = 0;
        for (int i = 1; i < good.size(); i++) {
            long dt = good.get(i).rxNanos() - good.get(i - 1).rxNanos();
            boolean boundary = false;
            boolean gap = false;
            if (!currentIntervals.isEmpty()) {
                long segMedian = median(currentIntervals);
                gap = dt > segMedian * GAP_FACTOR;
                // 速率切换需持续确认：下一个区间同样偏离才切段，
                // 单次抖动（如坏奇偶字被剔除后的补偿间隔）留在本段形成多候选
                boolean deviates = dt > segMedian * 1.8 || dt < segMedian / 1.8;
                boolean confirmed = false;
                if (deviates && i + 1 < good.size()) {
                    long next = good.get(i + 1).rxNanos() - good.get(i).rxNanos();
                    confirmed = next > segMedian * 1.8 || next < segMedian / 1.8;
                }
                boundary = gap || confirmed;
            }
            if (boundary) {
                out.add(build(channel, current, gapBefore, segNo++));
                current = new ArrayList<>();
                currentIntervals = new ArrayList<>();
                gapBefore = gap;
            }
            current.add(good.get(i));
            currentIntervals.add(dt);
        }
        out.add(build(channel, current, gapBefore, segNo));
        return out;
    }

    private static Segment build(String channel, List<WordPoint> seg, boolean gapBefore, int segNo) {
        Map<Long, Integer> histogram = new TreeMap<>();
        for (int i = 1; i < seg.size(); i++) {
            long dtMs = Math.round((seg.get(i).rxNanos() - seg.get(i - 1).rxNanos()) / 1_000_000.0);
            histogram.merge(dtMs, 1, Integer::sum);
        }
        List<RateCandidate> candidates = new ArrayList<>();
        int cNo = 0;
        for (Map.Entry<Long, Integer> e : histogram.entrySet()) {
            double intervalMs = e.getKey();
            double rateHz = intervalMs > 0 ? 1000.0 / intervalMs : 0;
            candidates.add(new RateCandidate(
                    channel + "-s" + segNo + "-c" + cNo++, rateHz, intervalMs, e.getValue(), "candidate"));
        }
        if (candidates.isEmpty()) {
            candidates.add(new RateCandidate(channel + "-s" + segNo + "-c0", 0, 0, 0, "candidate"));
        }
        return new Segment(channel + "-s" + segNo, channel,
                seg.get(0).seq(), seg.get(seg.size() - 1).seq(), gapBefore, candidates);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    /** 按通道分组。 */
    public static Map<String, List<WordPoint>> byChannel(List<WordPoint> words, List<String> channels) {
        Map<String, List<WordPoint>> map = new LinkedHashMap<>();
        for (String c : channels) map.put(c, new ArrayList<>());
        for (int i = 0; i < words.size(); i++) {
            map.computeIfAbsent(channels.get(i), k -> new ArrayList<>()).add(words.get(i));
        }
        return map;
    }
}
