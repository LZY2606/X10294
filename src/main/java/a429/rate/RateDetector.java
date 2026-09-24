package a429.rate;

import a429.decode.AdjacentGap;
import a429.decode.DecodedWord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a per-channel word stream at acquisition gaps and evaluates every
 * segment against the two legal ARINC 429 link rates. Ambiguous segments keep
 * all legal candidates; the adjudication layer never collapses them here.
 */
public final class RateDetector {
    public static final double TOLERANCE = 0.03; // 3 %
    public static final double GAP_RATIO = 2.5;

    private RateDetector() { }

    public record Result(List<AdjacentGap> gaps, List<RateSegment> segments) { }

    public static Result analyze(List<DecodedWord> words, String channel,
                                 int nominalRateBps, String runId) {
        List<DecodedWord> ws = words.stream()
                .filter(w -> w.channel().equals(channel))
                .sorted(java.util.Comparator.comparingInt(DecodedWord::seq))
                .toList();

        List<AdjacentGap> gaps = new ArrayList<>();
        double nominalPeriod = 3_200_000.0 / nominalRateBps;
        for (int i = 1; i < ws.size(); i++) {
            DecodedWord a = ws.get(i - 1);
            DecodedWord b = ws.get(i);
            long dt = b.tsMicros() - a.tsMicros();
            gaps.add(new AdjacentGap(
                    "gap:" + runId + ":" + channel + ":" + (i - 1),
                    channel, a.seq(), b.seq(), a.wordId(), b.wordId(),
                    dt, nominalPeriod, dt / nominalPeriod, dt / nominalPeriod >= GAP_RATIO));
        }

        // Cut segment boundaries at acquisition gaps.
        List<int[]> bounds = new ArrayList<>();
        List<Long> leadGaps = new ArrayList<>();
        int start = 0;
        long lead = 0;
        for (int gi = 0; gi < gaps.size(); gi++) {
            if (gaps.get(gi).acquisitionGap()) {
                bounds.add(new int[]{start, gi + 1});
                leadGaps.add(lead);
                start = gi + 1;
                lead = gaps.get(gi).deltaMicros();
            }
        }
        if (!ws.isEmpty()) {
            bounds.add(new int[]{start, ws.size()});
            leadGaps.add(lead);
        }

        List<RateSegment> segments = new ArrayList<>();
        for (int bi = 0; bi < bounds.size(); bi++) {
            int from = bounds.get(bi)[0];
            int to = bounds.get(bi)[1];
            segments.add(buildSegment(ws, from, to, leadGaps.get(bi), channel, runId, nominalRateBps));
        }
        return new Result(gaps, segments);
    }

    private static RateSegment buildSegment(List<DecodedWord> ws, int from, int to,
                                            long gapMicros, String channel,
                                            String runId, int nominalRate) {
        String segmentId = "seg:" + runId + ":" + channel + ":" + from + "-" + to;
        boolean gapBefore = gapMicros > 0;
        List<RateCandidate> cands = new ArrayList<>();
        int n = to - from;

        if (n >= 2) {
            long elapsed = ws.get(to - 1).tsMicros() - ws.get(from).tsMicros();
            double medianPeriod = elapsed / (double) (n - 1);
            double measured = 32.0 * 1_000_000.0 / medianPeriod;

            for (int rate : new int[]{12_500, 100_000}) {
                double expected = 3_200_000.0 / rate;
                double rel = Math.abs(medianPeriod - expected) / expected;
                if (rel <= TOLERANCE) {
                    String kind;
                    if (gapBefore) {
                        kind = "ACQUISITION_GAP";
                    } else if (rate != nominalRate) {
                        kind = "RATE_SWITCH";
                    } else {
                        kind = "STEADY";
                    }
                    cands.add(new RateCandidate(
                            "cand:" + runId + ":" + channel + ":" + from + "-" + to + ":" + rate,
                            segmentId, channel, ws.get(from).seq(), ws.get(to - 1).seq(),
                            n, measured, rate, rel, List.of(rate),
                            kind,
                            String.format(Locale.ROOT,
                                    "median period %.1f us within %.0f%% of %d bps",
                                    medianPeriod, TOLERANCE * 100, rate)));
                }
            }
            if (cands.isEmpty()) {
                cands.add(new RateCandidate(
                        "cand:" + runId + ":" + channel + ":" + from + "-" + to + ":unknown",
                        segmentId, channel, ws.get(from).seq(), ws.get(to - 1).seq(),
                        n, measured, 0, 1.0, List.of(),
                        "UNKNOWN_RATE",
                        String.format(Locale.ROOT,
                                "measured %.1f bps matches neither legal rate within tolerance",
                                measured)));
            }
        }

        return new RateSegment(segmentId, channel, ws.get(from).seq(), ws.get(to - 1).seq(),
                ws.get(from).tsMicros(), ws.get(to - 1).tsMicros(),
                gapBefore, gapMicros, cands);
    }
}
