package avdata.a429.decode;

import java.util.ArrayList;
import java.util.List;

/**
 * Link-rate review from inter-arrival gaps on one channel.
 *
 * <p>Rate changes and acquisition gaps are kept as segment candidates, never
 * collapsed at import time. When several nominal rates could explain a run all
 * legal candidates are returned.</p>
 */
public final class RateSegmenter {
    private RateSegmenter() {}

    public record Gap(int fromIndex, int toIndex, long dtUs, double hz, boolean acquisitionGap) {}

    public record Segment(int startIndex, int endIndex, long dtUs, double measuredHz,
                          List<Integer> candidateBps, String reason) {}

    /** Standard ARINC 429 link rates in bits per second. */
    public static final List<Integer> STANDARD_BPS = List.of(12500, 100000);

    private static final long GAP_FACTOR = 3;

    public static List<Gap> gaps(long[] timestampsUs) {
        List<Gap> gaps = new ArrayList<>();
        for (int i = 1; i < timestampsUs.length; i++) {
            long dt = timestampsUs[i] - timestampsUs[i - 1];
            double hz = dt <= 0 ? 0 : 1_000_000.0 / dt;
            gaps.add(new Gap(i - 1, i, dt, hz, false));
        }
        return gaps;
    }

    /**
     * Build segments. {@code wordsPerFrame} maps a bps rate to expected frame Hz
     * (32 bits/word): 12.5k -> up to ~390 words/s, 100k -> up to ~3125 words/s.
     * A gap of &gt;= 3x the running interval closes the current segment and is
     * reported as an acquisition boundary.
     */
    public static List<Segment> segments(long[] timestampsUs) {
        List<Segment> out = new ArrayList<>();
        if (timestampsUs.length < 2) {
            return out;
        }
        int start = 0;
        long running = timestampsUs[1] - timestampsUs[0];
        for (int i = 2; i <= timestampsUs.length; i++) {
            boolean end = i == timestampsUs.length;
            long dt = end ? 0 : timestampsUs[i] - timestampsUs[i - 1];
            if (!end && dt >= GAP_FACTOR * running) {
                out.add(emit(start, i - 1, timestampsUs, "RATE_CHANGE_OR_GAP"));
                start = i;
                running = dt;
            } else if (!end) {
                running = (running + dt) / 2;
            }
            if (end && start <= timestampsUs.length - 2) {
                out.add(emit(start, timestampsUs.length - 1, timestampsUs,
                        out.isEmpty() ? "CONTIGUOUS" : "POST_GAP"));
            }
        }
        return out;
    }

    private static Segment emit(int start, int end, long[] ts, String reason) {
        if (end <= start) {
            return new Segment(start, end, 0, 0, List.of(), "SINGLE_WORD_" + reason);
        }
        long dt = ts[end] - ts[start];
        double hz = (end - start) * 1_000_000.0 / dt;
        List<Integer> candidates = new ArrayList<>();
        for (int bps : STANDARD_BPS) {
            double maxWords = bps / 32.0;
            if (hz <= maxWords * 1.05) {
                candidates.add(bps);
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(STANDARD_BPS.get(STANDARD_BPS.size() - 1));
        }
        return new Segment(start, end, dt, hz, candidates, reason);
    }

    /** True when this inter-word dt is an acquisition gap rather than a rate switch. */
    public static boolean isAcquisitionGap(long dtUs, long baselineUs) {
        return baselineUs > 0 && dtUs >= GAP_FACTOR * baselineUs;
    }
}
