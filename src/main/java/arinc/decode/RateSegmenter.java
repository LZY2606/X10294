package arinc.decode;

import arinc.model.RawWord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Detects rate boundaries per (channel, label). Every boundary yields both an
 * ACQ_GAP and a RATE_SWITCH hypothesis; all legal candidates are retained and
 * adjudication happens later, never at import.
 */
public final class RateSegmenter {
  public record Boundary(String channel, String label, int afterSeq, long gapNs, long expectedNs) {}

  private RateSegmenter() {}

  public static List<Boundary> boundaries(List<RawWord> words, long expectedIntervalNs, double gapFactor) {
    Map<String, List<RawWord>> groups = new TreeMap<>();
    for (RawWord w : words) {
      groups.computeIfAbsent(w.channel() + "/" + w.labelOctal(), k -> new ArrayList<>()).add(w);
    }
    List<Boundary> out = new ArrayList<>();
    for (Map.Entry<String, List<RawWord>> e : groups.entrySet()) {
      List<RawWord> ws = e.getValue();
      for (int i = 1; i < ws.size(); i++) {
        long gap = ws.get(i).rxTimeNs() - ws.get(i - 1).rxTimeNs();
        if (gap > expectedIntervalNs * gapFactor) {
          out.add(new Boundary(ws.get(i).channel(), ws.get(i).labelOctal(),
              ws.get(i - 1).seq(), gap, expectedIntervalNs));
        }
      }
    }
    return out;
  }
}
