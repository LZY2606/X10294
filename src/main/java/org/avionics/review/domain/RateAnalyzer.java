package org.avionics.review.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes inter-word intervals and link-rate segmentation candidates.
 *
 * <p>Three legal hypotheses are emitted for every channel and none is hidden:
 * fixed-rate assumption, gap-driven segmentation and rate-adaptive
 * segmentation. Bad parity is irrelevant to physical arrival timing, so bad
 * words still take part in intervals; they are excluded only from engineering
 * aggregations downstream.</p>
 */
public final class RateAnalyzer {

    private final double gapThresholdMs;
    private final double rateSwitchRatio;

    public RateAnalyzer(double gapThresholdMs, double rateSwitchRatio) {
        this.gapThresholdMs = gapThresholdMs;
        this.rateSwitchRatio = rateSwitchRatio;
    }

    public List<Interval> intervals(List<RawWord> words) {
        Map<String, List<RawWord>> byChannel = new LinkedHashMap<>();
        for (RawWord w : words) {
            byChannel.computeIfAbsent(w.channel(), k -> new ArrayList<>()).add(w);
        }
        List<Interval> all = new ArrayList<>();
        for (Map.Entry<String, List<RawWord>> e : byChannel.entrySet()) {
            List<RawWord> stream = e.getValue();
            stream.sort((a, b) -> {
                int c = Double.compare(a.receiveTimeMs(), b.receiveTimeMs());
                return c != 0 ? c : Long.compare(a.seq(), b.seq());
            });
            for (int i = 1; i < stream.size(); i++) {
                RawWord prev = stream.get(i - 1);
                RawWord cur = stream.get(i);
                double delta = cur.receiveTimeMs() - prev.receiveTimeMs();
                all.add(new Interval(
                        "iv:" + e.getKey() + "#" + (i - 1),
                        e.getKey(), i - 1, prev.sid(), cur.sid(),
                        round(delta), delta > gapThresholdMs));
            }
        }
        all.sort((a, b) -> a.channel().compareTo(b.channel()));
        return all;
    }

    public List<RateCandidate> candidates(List<RawWord> words, List<Interval> intervals) {
        Map<String, List<RawWord>> wordsByChannel = new LinkedHashMap<>();
        Map<String, List<Interval>> ivByChannel = new LinkedHashMap<>();
        for (RawWord w : words) {
            wordsByChannel.computeIfAbsent(w.channel(), k -> new ArrayList<>()).add(w);
        }
        for (Interval iv : intervals) {
            ivByChannel.computeIfAbsent(iv.channel(), k -> new ArrayList<>()).add(iv);
        }

        List<RateCandidate> result = new ArrayList<>();
        for (String channel : wordsByChannel.keySet()) {
            List<RawWord> stream = wordsByChannel.get(channel);
            stream.sort((a, b) -> {
                int c = Double.compare(a.receiveTimeMs(), b.receiveTimeMs());
                return c != 0 ? c : Long.compare(a.seq(), b.seq());
            });
            List<Interval> ivs = ivByChannel.getOrDefault(channel, List.of());
            boolean anyGap = ivs.stream().anyMatch(Interval::acquisitionGap);
            boolean anySwitch = hasRateSwitch(ivs);

            result.add(buildCandidate(channel, "FIXED_RATE_ASSUMPTION", stream, ivs,
                    breakNothing(ivs), "One rate across the whole capture; gaps flagged only."));
            result.add(buildCandidate(channel, "GAP_DRIVEN", stream, ivs,
                    breakAtGaps(ivs), "Split only where inter-word gap exceeds "
                            + gapThresholdMs + " ms."));
            result.add(buildCandidate(channel, "RATE_ADAPTIVE", stream, ivs,
                    breakAtGapsAndSwitches(ivs), "Split at gaps and at >"
                            + Math.round(rateSwitchRatio * 100) + "% local rate changes.",
                    anyGap || anySwitch));
        }
        return result;
    }

    private boolean hasRateSwitch(List<Interval> ivs) {
        double prev = -1;
        for (Interval iv : ivs) {
            if (iv.acquisitionGap() || iv.deltaMs() <= 0) {
                prev = -1;
                continue;
            }
            double rate = iv.rateKhz();
            if (prev > 0) {
                double change = Math.abs(rate - prev) / prev;
                if (change > rateSwitchRatio) {
                    return true;
                }
            }
            prev = rate;
        }
        return false;
    }

    private List<Integer> breakNothing(List<Interval> ivs) {
        return new ArrayList<>();
    }

    private List<Integer> breakAtGaps(List<Interval> ivs) {
        List<Integer> breaks = new ArrayList<>();
        for (Interval iv : ivs) {
            if (iv.acquisitionGap()) {
                breaks.add(iv.indexInChannel());
            }
        }
        return breaks;
    }

    private List<Integer> breakAtGapsAndSwitches(List<Interval> ivs) {
        List<Integer> breaks = breakAtGaps(ivs);
        double prev = -1;
        for (Interval iv : ivs) {
            if (iv.acquisitionGap() || iv.deltaMs() <= 0) {
                prev = -1;
                continue;
            }
            double rate = iv.rateKhz();
            if (prev > 0) {
                double change = Math.abs(rate - prev) / prev;
                if (change > rateSwitchRatio && !breaks.contains(iv.indexInChannel())) {
                    breaks.add(iv.indexInChannel());
                }
            }
            prev = rate;
        }
        return breaks;
    }

    private RateCandidate buildCandidate(String channel, String method, List<RawWord> stream,
                                         List<Interval> ivs, List<Integer> breakIndexes,
                                         String rationale) {
        return buildCandidate(channel, method, stream, ivs, breakIndexes, rationale, false);
    }

    private RateCandidate buildCandidate(String channel, String method, List<RawWord> stream,
                                         List<Interval> ivs, List<Integer> breakIndexes,
                                         String rationale, boolean preferred) {
        List<RateSegment> segments = new ArrayList<>();
        int segStart = 0;
        int segNo = 0;
        for (int breakAt : breakIndexes) {
            int segEnd = breakAt;
            segments.add(segment(channel, method, segNo, segStart, segEnd, stream, ivs));
            segStart = segEnd + 1;
            segNo++;
        }
        segments.add(segment(channel, method, segNo, segStart, stream.size() - 1, stream, ivs));
        String candSid = "rc:" + channel + ":" + method;
        List<RateSegment> withSid = new ArrayList<>();
        for (RateSegment s : segments) {
            withSid.add(new RateSegment(
                    "rs:" + channel + ":" + method + "#" + s.startIndex() + "-" + s.endIndex(),
                    s.candidateMethod(), s.channel(), s.startIndex(), s.endIndex(),
                    s.startMs(), s.endMs(), s.wordCount(), s.rateKhz(), s.gapBefore(),
                    s.intervals()));
        }
        return new RateCandidate(candSid, channel, method, rationale, preferred, withSid);
    }

    private RateSegment segment(String channel, String method, int segNo, int start, int end,
                                List<RawWord> stream, List<Interval> ivs) {
        List<Interval> inside = new ArrayList<>();
        boolean gapBefore = false;
        for (Interval iv : ivs) {
            if (iv.indexInChannel() >= start && iv.indexInChannel() < end) {
                inside.add(iv);
            }
            if (iv.indexInChannel() == start - 1 && iv.acquisitionGap()) {
                gapBefore = true;
            }
        }
        double rate = inside.isEmpty() ? 0
                : inside.stream().filter(i -> !i.acquisitionGap())
                        .mapToDouble(Interval::rateKhz).average().orElse(0);
        return new RateSegment(
                "seg", method, channel, start, end,
                stream.get(start).receiveTimeMs(),
                stream.get(end).receiveTimeMs(),
                end - start + 1, round(rate), gapBefore, inside);
    }

    private static double round(double v) {
        return Math.round(v * 1e6d) / 1e6d;
    }
}
