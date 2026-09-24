package a429.rate;

import a429.ids.Ids;
import a429.model.RateParams;
import a429.model.RawCapture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds adjacent-word intervals and enumerates every rate segmentation compatible with the
 * measured gaps. ARINC 429 defines 12_500 and 100_000 bit/s; nominal word spacing is 32/rate.
 *
 *  - An interval compatible with a rate deviates no more than {@code toleranceRatio} from nominal.
 *  - A gap wider than {@code gapFactor} x the low-rate spacing is an acquisition gap.
 *  - A rate change between adjacent intervals is a definite segment boundary.
 *  - An acquisition gap MAY split the stream or be bridged when the rates on both sides match:
 *    both readings are legal, so all 2^g candidate segmentations are retained (deterministic
 *    order, capped at params.maxCandidates) for human adjudication.
 */
public final class RateSegmenter {
    public static final int[] RATES = {RateParams.RATE_HIGH, RateParams.RATE_LOW};

    private RateSegmenter() {}

    public static Map<String, List<Interval>> intervalsByChannel(List<RawCapture> captures,
                                                                 Set<Integer> badParitySeq) {
        Map<String, List<RawCapture>> byChannel = new LinkedHashMap<>();
        for (RawCapture capture : captures) {
            byChannel.computeIfAbsent(capture.channel(), k -> new ArrayList<>()).add(capture);
        }
        Map<String, List<Interval>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<RawCapture>> entry : byChannel.entrySet()) {
            List<RawCapture> list = entry.getValue();
            list.sort(Comparator.comparingInt(RawCapture::seq));
            List<Interval> intervals = new ArrayList<>();
            for (int i = 1; i < list.size(); i++) {
                RawCapture left = list.get(i - 1);
                RawCapture right = list.get(i);
                intervals.add(new Interval(left.seq(), right.seq(),
                        right.tsNanos() - left.tsNanos(),
                        badParitySeq.contains(left.seq()),
                        badParitySeq.contains(right.seq())));
            }
            result.put(entry.getKey(), intervals);
        }
        return result;
    }

    public static double nominalSpacingNanos(int rateBps) {
        return 32.0 * 1_000_000_000.0 / rateBps;
    }

    public static boolean compatible(long gapNanos, int rateBps, RateParams params) {
        double nominal = nominalSpacingNanos(rateBps);
        return Math.abs(gapNanos - nominal) <= nominal * params.toleranceRatio();
    }

    public static boolean isAcquisitionGap(long gapNanos, RateParams params) {
        return gapNanos > nominalSpacingNanos(RateParams.RATE_LOW) * params.gapFactor();
    }

    /** Rates compatible with one interval; empty means no legal rate (diagnostic interval). */
    public static List<Integer> compatibleRates(long gapNanos, RateParams params) {
        List<Integer> rates = new ArrayList<>();
        for (int rate : RATES) {
            if (compatible(gapNanos, rate, params)) {
                rates.add(rate);
            }
        }
        return rates;
    }

    public static List<RateCandidate> segment(String channel, List<Interval> intervals,
                                              RateParams params) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<Integer> gapIndexes = new ArrayList<>();
        List<Integer> intervalRates = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            if (isAcquisitionGap(interval.gapNanos(), params)) {
                gapIndexes.add(i);
                intervalRates.add(0);
            } else {
                List<Integer> rates = compatibleRates(interval.gapNanos(), params);
                intervalRates.add(rates.size() == 1 ? rates.get(0) : -1);
            }
        }

        int combinations = 1 << gapIndexes.size();
        List<RateCandidate> candidates = new ArrayList<>();
        for (int mask = 0; mask < combinations && mask < params.maxCandidates(); mask++) {
            List<RateSegment> segments = buildSegments(intervals, intervalRates, gapIndexes, mask);
            List<Integer> bridged = bridgedGapSeq(intervals, gapIndexes, mask);
            String uid = Ids.rateCandidate(channel, params.version(), segments, bridged);
            candidates.add(new RateCandidate(uid, mask, channel, segments, bridged));
        }
        return candidates;
    }

    private static List<RateSegment> buildSegments(List<Interval> intervals,
                                                   List<Integer> intervalRates,
                                                   List<Integer> gapIndexes, int mask) {
        Set<Integer> splitGaps = splitGapIndexes(gapIndexes, mask);
        List<RateSegment> segments = new ArrayList<>();
        int currentRate = -1;
        int segmentStart = intervals.get(0).leftSeq();
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            int rate = intervalRates.get(i);
            if (rate == 0) {
                boolean split = splitGaps.contains(i);
                if (split && currentRate != -1) {
                    segments.add(new RateSegment(currentRate, segmentStart, interval.leftSeq()));
                    currentRate = -1;
                }
                continue;
            }
            if (currentRate == -1) {
                currentRate = rate;
                segmentStart = interval.leftSeq();
            } else if (rate != -1 && rate != currentRate) {
                segments.add(new RateSegment(currentRate, segmentStart, interval.leftSeq()));
                currentRate = rate;
                segmentStart = interval.leftSeq();
            }
        }
        Interval last = intervals.get(intervals.size() - 1);
        if (currentRate != -1) {
            segments.add(new RateSegment(currentRate, segmentStart, last.rightSeq()));
        }
        return List.copyOf(segments);
    }

    private static Set<Integer> splitGapIndexes(List<Integer> gapIndexes, int mask) {
        Set<Integer> split = new java.util.HashSet<>();
        for (int g = 0; g < gapIndexes.size(); g++) {
            if ((mask & (1 << g)) != 0) {
                split.add(gapIndexes.get(g));
            }
        }
        return split;
    }

    private static List<Integer> bridgedGapSeq(List<Interval> intervals,
                                               List<Integer> gapIndexes, int mask) {
        List<Integer> bridged = new ArrayList<>();
        for (int g = 0; g < gapIndexes.size(); g++) {
            if ((mask & (1 << g)) == 0) {
                bridged.add(intervals.get(gapIndexes.get(g)).leftSeq());
            }
        }
        return bridged;
    }
}
