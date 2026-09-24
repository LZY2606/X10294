package com.avionics.a429.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a channel's per-label word stream into rate segments.
 *
 * Segment boundaries come from acquisition gaps (interval above the configured
 * gap threshold) and rate switches (interval ratio changing beyond the switch
 * ratio). When the measured rate lies within tolerance of more than one
 * standard rate every legal candidate is retained. The interval that crosses a
 * boundary is never averaged into either run.
 */
public final class RateSegmenter {

    public List<RateSegment> segments(List<Word> words,
                                      Map<String, Decode> decodes, RateParams p) {
        Map<String, List<Word>> groups = new LinkedHashMap<>();
        for (Word w : words) {
            Decode d = decodes.get(w.id);
            if (d == null) {
                continue;
            }
            String key = w.channel + "|" + d.labelOctal;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
        }
        List<RateSegment> out = new ArrayList<>();
        for (Map.Entry<String, List<Word>> e : groups.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            List<Word> ws = e.getValue();
            ws.sort(Comparator.comparingLong(w -> w.receiveNanos));
            if (ws.size() == 1) {
                out.add(single(parts[0], parts[1], ws.get(0)));
                continue;
            }
            int runStart = 0;
            long runSum = 0;
            int runCount = 0;
            boolean pendingGap = false;
            for (int i = 1; i < ws.size(); i++) {
                long interval = ws.get(i).receiveNanos - ws.get(i - 1).receiveNanos;
                boolean gap = interval > p.gapNanos();
                boolean switchHit = !gap && runCount >= p.minIntervals()
                        && Math.abs(interval - runSum / (double) runCount)
                            / (runSum / (double) runCount) > p.switchRatio();
                if (gap || switchHit) {
                    out.add(build(parts[0], parts[1], ws, runStart, i - 1,
                            runSum, runCount, p, gap ? "ACQUISITION_GAP" : "RATE_SWITCH"));
                    runStart = i;
                    runSum = 0;
                    runCount = 0;
                    pendingGap = gap;
                } else {
                    runSum += interval;
                    runCount++;
                    pendingGap = false;
                }
            }
            String tailReason = pendingGap ? "AFTER_ACQUISITION_GAP" : "RUN";
            out.add(build(parts[0], parts[1], ws, runStart, ws.size() - 1,
                    runSum, runCount, p, tailReason));
        }
        return out;
    }

    private RateSegment single(String channel, String label, Word w) {
        return new RateSegment("seg:" + channel + ":" + label + ":" + w.sequence + "-"
                + w.sequence, channel, label, (int) w.sequence, (int) w.sequence, 1,
                0, 0.0, new ArrayList<>(), "SINGLE_WORD_NO_RATE", false);
    }

    private RateSegment build(String channel, String label, List<Word> ws,
                              int startIdx, int endIdx, long sum, int intervals,
                              RateParams p, String reason) {
        Word first = ws.get(startIdx);
        Word last = ws.get(endIdx);
        long mean = intervals == 0 ? 0 : sum / intervals;
        double hz = mean == 0 ? 0.0 : 1_000_000_000.0 / mean;
        List<Double> candidates = new ArrayList<>();
        for (double std : RateParams.STANDARD_HZ) {
            if (Math.abs(hz - std) / std <= p.candidateTolerance()) {
                candidates.add(std);
            }
        }
        if (intervals > 0 && candidates.isEmpty()) {
            reason += ":NO_STANDARD_RATE_CANDIDATE";
        }
        return new RateSegment(
                "seg:" + channel + ":" + label + ":" + first.sequence + "-" + last.sequence,
                channel, label, (int) first.sequence, (int) last.sequence,
                endIdx - startIdx + 1, mean, Math.rint(hz * 1000.0) / 1000.0,
                candidates, reason, reason.contains("GAP"));
    }
}
