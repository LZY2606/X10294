package gsb;

import java.util.ArrayList;
import java.util.List;

/** 链路速率分段：速率变化与采集缺口都保留为候选，由人工裁定。 */
public final class Rate {
    private Rate() {}

    public record Candidate(int boundarySeq, String reason, long gapNs, double ratioToNominal) {}
    public record Segment(int startSeq, int endSeq, long nominalGapNs) {}

    /** 相邻字间隔候选。boundarySeq 表示在该序号之前切分。 */
    public static List<Candidate> candidates(List<long[]> seqAndRxNs) {
        List<Candidate> out = new ArrayList<>();
        int n = seqAndRxNs.size();
        if (n < 2) {
            return out;
        }
        long[] gaps = new long[n - 1];
        for (int i = 0; i < n - 1; i++) {
            gaps[i] = seqAndRxNs.get(i + 1)[1] - seqAndRxNs.get(i)[1];
        }
        long nominal = mode(gaps);
        long prevGap = -1;
        for (int i = 0; i < gaps.length; i++) {
            long g = gaps[i];
            double ratio = nominal == 0 ? 0 : (double) g / nominal;
            int boundary = (int) seqAndRxNs.get(i + 1)[0];
            if (nominal > 0 && g >= 5 * nominal) {
                out.add(new Candidate(boundary, "ACQ_GAP", g, ratio));
            } else if (nominal > 0 && (g >= 2.5 * nominal || g <= nominal / 2.5)) {
                boolean sustained = prevGap < 0 || Math.abs(g - prevGap) > Math.max(g, prevGap) / 5;
                if (sustained) {
                    out.add(new Candidate(boundary, "RATE_SWITCH", g, ratio));
                }
            }
            prevGap = g;
        }
        return out;
    }

    /** 按已接受的边界集合切分段。 */
    public static List<Segment> segments(List<long[]> seqAndRxNs, List<Integer> acceptedBoundaries) {
        List<Segment> out = new ArrayList<>();
        if (seqAndRxNs.isEmpty()) {
            return out;
        }
        int start = (int) seqAndRxNs.get(0)[0];
        long prevRx = seqAndRxNs.get(0)[1];
        for (int i = 1; i < seqAndRxNs.size(); i++) {
            int seq = (int) seqAndRxNs.get(i)[0];
            long rx = seqAndRxNs.get(i)[1];
            if (acceptedBoundaries.contains(seq)) {
                out.add(new Segment(start, (int) seqAndRxNs.get(i - 1)[0], rx - prevRx));
                start = seq;
            }
            prevRx = rx;
        }
        out.add(new Segment(start, (int) seqAndRxNs.get(seqAndRxNs.size() - 1)[0], 0));
        return out;
    }

    private static long mode(long[] gaps) {
        long best = gaps[0];
        int bestCount = 0;
        for (long g : gaps) {
            int count = 0;
            for (long h : gaps) {
                if (h == g) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = g;
            }
        }
        return best;
    }
}
