package a429.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 速率分段：按通道把相邻字间隔超过配置阈值处切分为候选段。
 * 速率切换与采集缺口都保留分段候选；无法唯一归属 HIGH/LOW 时给出全部合法候选，
 * 由人工裁定，不在导入时清洗。
 */
public final class RateSegmenter {

    /** 一个分段：一个通道内时间连续的若干字。 */
    public record Segment(
            String id,
            String channel,
            int index,
            long startSeq,
            long endSeq,
            double firstRecvUs,
            double lastRecvUs,
            int wordCount,
            List<Double> gapsUs,
            double medianGapUs,
            List<Candidate> candidates,
            boolean gapBefore
    ) {
    }

    /** 合法速率候选（保留全部）。 */
    public record Candidate(
            String basis,       // MEASURED | CONFIG_HIGH | CONFIG_LOW
            double rateHz,
            double wordIntervalUs,
            double deviationPct,
            String note
    ) {
    }

    private RateSegmenter() {
    }

    public static List<Segment> segment(List<IngestWord> words, ChannelConfig config) {
        List<IngestWord> in = words.stream()
                .filter(w -> w.channel().equals(config.channel()))
                .sorted(java.util.Comparator.comparingLong(IngestWord::recvUs)
                        .thenComparingLong(IngestWord::seq))
                .toList();

        List<Segment> segments = new ArrayList<>();
        int segIndex = 0;
        List<IngestWord> cur = new ArrayList<>();
        boolean gapBefore = false;

        for (int i = 0; i < in.size(); i++) {
            IngestWord w = in.get(i);
            if (cur.isEmpty()) {
                cur.add(w);
                continue;
            }
            double gapUs = w.recvUs() - cur.get(cur.size() - 1).recvUs();
            if (gapUs > config.gapThresholdMs() * 1000.0) {
                segments.add(build(config, segIndex++, cur, gapBefore));
                gapBefore = true;
                cur = new ArrayList<>();
            }
            cur.add(w);
        }
        if (!cur.isEmpty()) {
            segments.add(build(config, segIndex, cur, gapBefore));
        }
        return segments;
    }

    private static Segment build(ChannelConfig config, int index, List<IngestWord> ws, boolean gapBefore) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < ws.size(); i++) {
            gaps.add((double) (ws.get(i).recvUs() - ws.get(i - 1).recvUs()));
        }
        double median = median(gaps);
        List<Candidate> candidates = new ArrayList<>();
        if (median > 0) {
            candidates.add(new Candidate("MEASURED", 1_000_000.0 / median, median, 0.0,
                    "由相邻字间隔中位数直接测得"));
        }
        double cfgInterval = config.nominalWordIntervalUs();
        double deviation = median > 0 ? Math.abs(median - cfgInterval) / cfgInterval * 100.0 : 0.0;
        String basis = "LOW_12_5K".equals(config.nominalSpeed()) ? "CONFIG_LOW" : "CONFIG_HIGH";
        candidates.add(new Candidate(basis, 1_000_000.0 / cfgInterval, cfgInterval,
                deviation, "通道配置 " + config.nominalSpeed()));
        // 另一链路等级同样作为合法候选保留，供速率切换场景裁定
        String otherBasis = "CONFIG_LOW".equals(basis) ? "CONFIG_HIGH" : "CONFIG_LOW";
        double otherInterval = "CONFIG_LOW".equals(basis)
                ? ChannelConfig.HIGH_WORD_INTERVAL_US : 800.0;
        double otherDev = median > 0 ? Math.abs(median - otherInterval) / otherInterval * 100.0 : 0.0;
        candidates.add(new Candidate(otherBasis, 1_000_000.0 / otherInterval, otherInterval,
                otherDev, "相对链路等级候选"));
        return new Segment(
                "seg-" + config.channel() + "-" + index,
                config.channel(), index,
                ws.get(0).seq(), ws.get(ws.size() - 1).seq(),
                ws.get(0).recvUs(), ws.get(ws.size() - 1).recvUs(),
                ws.size(), List.copyOf(gaps), median, List.copyOf(candidates), gapBefore
        );
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double m = (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return round1(m);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public static Map<String, Object> candidateMap(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("basis", c.basis());
        m.put("rateHz", String.format(Locale.ROOT, "%.1f", c.rateHz()));
        m.put("wordIntervalUs", c.wordIntervalUs());
        m.put("deviationPct", String.format(Locale.ROOT, "%.1f", c.deviationPct()));
        m.put("note", c.note());
        return m;
    }
}
