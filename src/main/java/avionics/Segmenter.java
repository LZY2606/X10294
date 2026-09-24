package avionics;

import java.util.ArrayList;
import java.util.List;

/**
 * Rate segmentation. Rate changes and acquisition gaps are kept as
 * segmentation candidates instead of being resolved at import time.
 * When a boundary cannot be uniquely decided, every legal candidate is kept.
 */
public final class Segmenter {

    private Segmenter() {}

    public record WordPoint(String wordId, long recvTimeNs, String channel, String labelOctal) {}

    public record SegmentCandidate(
            String kind,          // ACQUISITION_GAP | RATE_SWITCH | STEADY_RATE
            String channel,
            String labelOctal,
            String beforeWordId,
            String afterWordId,
            long intervalNs,
            double observedRateHz,
            String rationale
    ) {}

    /** Expected interval in ns per channel, e.g. {"A": 50_000_000}. */
    public record ChannelConfig(java.util.Map<String, Long> expectedIntervalNs) {
        public long expectedFor(String channel) {
            return expectedIntervalNs.getOrDefault(channel, 50_000_000L);
        }
    }

    /**
     * Examine adjacent words of the same channel+label. A boundary where the
     * interval exceeds 2.5x the expected interval yields both an
     * ACQUISITION_GAP and a RATE_SWITCH candidate (ambiguity preserved);
     * a sustained new interval yields a RATE_SWITCH candidate.
     */
    public static List<SegmentCandidate> analyze(List<WordPoint> words, ChannelConfig cfg) {
        List<SegmentCandidate> out = new ArrayList<>();
        List<WordPoint> sorted = new ArrayList<>(words);
        sorted.sort(java.util.Comparator.comparing(WordPoint::recvTimeNs));
        for (int i = 0; i + 1 < sorted.size(); i++) {
            WordPoint a = sorted.get(i);
            WordPoint b = sorted.get(i + 1);
            if (!a.channel().equals(b.channel()) || !a.labelOctal().equals(b.labelOctal())) continue;
            long expected = cfg.expectedFor(a.channel());
            long dt = b.recvTimeNs() - a.recvTimeNs();
            if (dt > expected * 2.5) {
                double rate = dt > 0 ? 1e9 / dt : 0;
                // Look ahead: is the new interval sustained (rate switch) or isolated (gap)?
                boolean sustained = false;
                if (i + 2 < sorted.size()) {
                    WordPoint c = sorted.get(i + 2);
                    if (c.channel().equals(b.channel()) && c.labelOctal().equals(b.labelOctal())) {
                        long dt2 = c.recvTimeNs() - b.recvTimeNs();
                        sustained = Math.abs(dt2 - dt) <= expected;
                    }
                }
                // Ambiguity is preserved: both legal candidates are recorded.
                out.add(new SegmentCandidate("ACQUISITION_GAP", a.channel(), a.labelOctal(),
                        a.wordId(), b.wordId(), dt, rate,
                        "interval " + dt + "ns > 2.5x expected " + expected + "ns; may be a capture gap"));
                out.add(new SegmentCandidate("RATE_SWITCH", a.channel(), a.labelOctal(),
                        a.wordId(), b.wordId(), dt, rate,
                        sustained
                                ? "interval " + dt + "ns is sustained by following word(s): likely rate switch"
                                : "interval " + dt + "ns could be a new link rate; not uniquely decidable"));
            }
        }
        return out;
    }
}
