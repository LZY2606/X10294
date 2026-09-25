package com.avionics.a429.core;

import com.avionics.a429.core.Models.GapInfo;
import com.avionics.a429.core.Models.SegmentCandidate;
import com.avionics.a429.core.Models.SourceItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 链路速率分段。速率变化与采集缺口都保留为分段候选；
 * 无法唯一决定时间隔保留全部合法候选，不做不可逆判定。
 */
public final class RateSegmenter {
    private RateSegmenter() {}

    /** 相邻间隔容差：标称间隔的 ±35%。 */
    public static final double TOLERANCE = 0.35;
    /** 超过低速标称间隔 3 倍视为采集缺口。 */
    public static final double GAP_FACTOR = 3.0;

    public static double nominalIntervalMs(double bitsPerSec, double assumeGapBits) {
        return (ArincWord.WORD_BITS + assumeGapBits) / bitsPerSec * 1000.0;
    }

    /** 计算一个通道内相邻字间隔及速率候选。 */
    public static List<GapInfo> gaps(String channel, List<SourceItem> words, double assumeGapBits) {
        List<GapInfo> out = new ArrayList<>();
        double hiNom = nominalIntervalMs(ArincWord.HIGH_BITS_PER_SEC, assumeGapBits);
        double loNom = nominalIntervalMs(ArincWord.LOW_BITS_PER_SEC, assumeGapBits);
        for (int i = 0; i + 1 < words.size(); i++) {
            SourceItem a = words.get(i), b = words.get(i + 1);
            String uid = "gap:" + channel + ":" + String.format("%03d", a.seq());
            if (a.rxNanos() == null || b.rxNanos() == null) {
                out.add(new GapInfo(uid, channel, a.seq(), Double.NaN, Double.NaN, Double.NaN,
                        List.of("UNKNOWN"), "UNKNOWN"));
                continue;
            }
            double dtMs = (b.rxNanos() - a.rxNanos()) / 1_000_000.0;
            double bitsHi = dtMs * ArincWord.HIGH_BITS_PER_SEC / 1000.0;
            double bitsLo = dtMs * ArincWord.LOW_BITS_PER_SEC / 1000.0;
            List<String> candidates = new ArrayList<>();
            boolean hi = within(dtMs, hiNom);
            boolean lo = within(dtMs, loNom);
            if (hi) candidates.add("HIGH");
            if (lo) candidates.add("LOW");
            String classification;
            if (dtMs > loNom * GAP_FACTOR) {
                candidates.add("GAP");
                classification = "GAP";
            } else if (hi && lo) {
                classification = "AMBIGUOUS";
            } else if (hi) {
                classification = "HIGH";
            } else if (lo) {
                classification = "LOW";
            } else {
                candidates.add("UNKNOWN");
                classification = "UNKNOWN";
            }
            out.add(new GapInfo(uid, channel, a.seq(), dtMs, bitsHi, bitsLo,
                    List.copyOf(candidates), classification));
        }
        return out;
    }

    private static boolean within(double dtMs, double nominal) {
        return dtMs >= nominal * (1 - TOLERANCE) && dtMs <= nominal * (1 + TOLERANCE);
    }

    /**
     * 由间隔分类构造分段候选。GAP/UNKNOWN 间隔形成边界候选；
     * 每段保留全部合法速率候选，裁定状态由 adjudications 给出。
     */
    public static List<SegmentCandidate> segments(String channel, List<SourceItem> words,
                                                  List<GapInfo> gaps,
                                                  Map<String, Map<String, String>> adjudications) {
        List<SegmentCandidate> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        int segStart = words.get(0).seq();
        String currentRate = null;
        List<String> currentAlts = new ArrayList<>();
        int segIndex = 1;
        for (int i = 0; i < gaps.size(); i++) {
            GapInfo g = gaps.get(i);
            String rate = switch (g.classification()) {
                case "HIGH" -> "HIGH";
                case "LOW" -> "LOW";
                default -> "BOUNDARY";
            };
            if ("BOUNDARY".equals(rate)) {
                out.add(makeSegment(channel, segIndex++, segStart, words.get(i).seq(),
                        currentRate == null ? "UNKNOWN" : currentRate, currentAlts, adjudications));
                segStart = words.get(i + 1).seq();
                currentRate = null;
                currentAlts = new ArrayList<>();
            } else {
                if (currentRate == null) {
                    currentRate = rate;
                    currentAlts = new ArrayList<>(g.candidates());
                } else if (!currentRate.equals(rate)) {
                    // 速率切换：保留切换点两侧分段候选
                    out.add(makeSegment(channel, segIndex++, segStart, words.get(i).seq(),
                            currentRate, currentAlts, adjudications));
                    segStart = words.get(i + 1).seq();
                    currentRate = rate;
                    currentAlts = new ArrayList<>(g.candidates());
                }
            }
        }
        out.add(makeSegment(channel, segIndex, segStart, words.get(words.size() - 1).seq(),
                currentRate == null ? "UNKNOWN" : currentRate, currentAlts, adjudications));
        return out;
    }

    private static SegmentCandidate makeSegment(String channel, int index, int start, int end,
                                                String rate, List<String> alts,
                                                Map<String, Map<String, String>> adjudications) {
        String uid = "seg:" + channel + ":" + index;
        List<String> alternatives = alts.isEmpty() ? List.of(rate) : List.copyOf(alts);
        String status = "CANDIDATE";
        String by = null;
        Map<String, String> adj = adjudications.get(uid);
        if (adj != null) {
            status = adj.getOrDefault("decision", "CANDIDATE");
            by = adj.get("eventId");
        }
        return new SegmentCandidate(uid, channel, start, end, rate, alternatives, status, by);
    }
}
