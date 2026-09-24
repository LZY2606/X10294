package avionics.arinc;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路速率分段：速率变化与采集缺口都保留为分段候选；
 * 无法唯一决定时（间隔落在 1.5x~2.5x 标称区间）同时保留全部合法候选。
 */
public final class RateSegmenter {
    public record Point(int seq, long rxNs) {}

    public record Candidate(String kind, int fromSeq, int toSeq, double intervalMs, String basis) {}
    // kind: steady | rate-switch | acquisition-gap

    private RateSegmenter() {}

    public static List<Candidate> segment(List<Point> pts, double nominalMs) {
        List<Candidate> out = new ArrayList<>();
        if (pts.size() < 2) return out;
        double nominal = nominalMs;
        int segStart = pts.get(0).seq();
        int prev = pts.get(0).seq();
        for (int i = 1; i < pts.size(); i++) {
            double gapMs = (pts.get(i).rxNs() - pts.get(i - 1).rxNs()) / 1_000_000.0;
            double ratio = gapMs / nominal;
            if (ratio >= 0.75 && ratio <= 1.25) {
                continue; // 稳态延续
            }
            out.add(new Candidate("steady", segStart, prev, nominal,
                    "steady segment @" + trim(nominal) + "ms"));
            if (ratio > 2.5) {
                out.add(new Candidate("acquisition-gap", prev, pts.get(i).seq(), gapMs,
                        "gap " + trim(gapMs) + "ms > 2.5x nominal " + trim(nominal) + "ms"));
            } else if (ratio > 1.5) {
                // 模糊区：既是速率切换也是采集缺口的合法候选，全部保留
                out.add(new Candidate("rate-switch", prev, pts.get(i).seq(), gapMs,
                        "ambiguous: " + trim(gapMs) + "ms in 1.5x..2.5x of " + trim(nominal) + "ms"));
                out.add(new Candidate("acquisition-gap", prev, pts.get(i).seq(), gapMs,
                        "ambiguous: " + trim(gapMs) + "ms in 1.5x..2.5x of " + trim(nominal) + "ms"));
            } else {
                out.add(new Candidate("rate-switch", prev, pts.get(i).seq(), gapMs,
                        "rate switch " + trim(nominal) + "ms -> " + trim(gapMs) + "ms"));
                // 若下一间隔同样落在新速率上，则确认切换并更新标称值
                if (i + 1 < pts.size()) {
                    double next = (pts.get(i + 1).rxNs() - pts.get(i).rxNs()) / 1_000_000.0;
                    if (Math.abs(next - gapMs) <= 0.25 * gapMs) nominal = gapMs;
                }
            }
            segStart = pts.get(i).seq();
            prev = pts.get(i).seq();
        }
        out.add(new Candidate("steady", segStart, prev, nominal, "steady segment @" + trim(nominal) + "ms"));
        return out;
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return Double.toString(v);
    }
}
