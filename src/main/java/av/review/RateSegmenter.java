package av.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derived layer: adjacent-word intervals and rate segment candidates.
 * Rate changes and acquisition gaps are preserved as candidates; nothing is
 * collapsed into a single "true" segmentation without an adjudication event.
 */
public final class RateSegmenter {

    private RateSegmenter() {
    }

    /** Adjacent intervals per (channel, label) stream, in original sequence order. */
    public static List<Map<String, Object>> intervals(List<WordRec> words) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, WordRec> lastByStream = new LinkedHashMap<>();
        List<WordRec> sorted = new ArrayList<>(words);
        sorted.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        for (WordRec w : sorted) {
            String stream = w.channel() + "|" + w.labelOctal();
            WordRec prev = lastByStream.get(stream);
            if (prev != null) {
                Map<String, Object> iv = new LinkedHashMap<>();
                iv.put("id", "iv:" + prev.id() + "->" + w.id());
                iv.put("stream", stream);
                iv.put("fromWordId", prev.id());
                iv.put("toWordId", w.id());
                iv.put("fromSeq", prev.seq());
                iv.put("toSeq", w.seq());
                iv.put("intervalNs", w.rxTimeNs() - prev.rxTimeNs());
                iv.put("unit", "ns");
                out.add(iv);
            }
            lastByStream.put(stream, w);
        }
        return out;
    }

    /**
     * Segment candidates per stream. A boundary candidate is emitted when an
     * interval exceeds {@code gapFactor} times the running median interval
     * (acquisition gap) or when the sustained interval clearly changes (rate
     * switch). Every candidate keeps a deterministic id so identities survive
     * export/import round trips.
     */
    public static List<Map<String, Object>> candidates(List<WordRec> words, Map<String, Object> dict) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<WordRec>> streams = new LinkedHashMap<>();
        List<WordRec> sorted = new ArrayList<>(words);
        sorted.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        for (WordRec w : sorted) {
            streams.computeIfAbsent(w.channel() + "|" + w.labelOctal(), k -> new ArrayList<>()).add(w);
        }
        for (Map.Entry<String, List<WordRec>> e : streams.entrySet()) {
            List<WordRec> ws = e.getValue();
            if (ws.size() < 2) {
                continue;
            }
            String label = ws.get(0).labelOctal();
            Map<String, Object> entry = Interpreter.entryOf(dict, label);
            double expectedMs = entry != null && entry.get("rateMs") != null
                    ? ((Number) entry.get("rateMs")).doubleValue() : 0;
            List<Long> ivs = new ArrayList<>();
            for (int i = 1; i < ws.size(); i++) {
                ivs.add(ws.get(i).rxTimeNs() - ws.get(i - 1).rxTimeNs());
            }
            long median = median(ivs);
            List<Integer> boundaries = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (int i = 0; i < ivs.size(); i++) {
                long iv = ivs.get(i);
                boolean gap = median > 0 && iv > median * 2;
                boolean rateChange = median > 0 && i + 1 < ivs.size()
                        && Math.abs(ivs.get(i + 1) - median) * 2 > median && iv <= median * 2;
                if (gap || rateChange) {
                    boundaries.add(i + 1); // index into ws where the new segment starts
                    reasons.add(gap ? "acquisition-gap" : "rate-switch");
                }
            }
            int segIndex = 0;
            int start = 0;
            List<Integer> cuts = new ArrayList<>(boundaries);
            cuts.add(ws.size());
            for (int b = 0; b < cuts.size(); b++) {
                int end = cuts.get(b);
                List<WordRec> seg = ws.subList(start, end);
                long first = seg.get(0).rxTimeNs();
                long last = seg.get(seg.size() - 1).rxTimeNs();
                double meanMs = seg.size() > 1 ? (last - first) / 1e6 / (seg.size() - 1) : 0;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", "rate:" + e.getKey() + ":" + seg.get(0).id() + ":" + seg.get(seg.size() - 1).id());
                c.put("kind", "rate-segment");
                c.put("stream", e.getKey());
                c.put("channel", seg.get(0).channel());
                c.put("labelOctal", label);
                c.put("fromWordId", seg.get(0).id());
                c.put("toWordId", seg.get(seg.size() - 1).id());
                c.put("wordCount", seg.size());
                c.put("startNs", first);
                c.put("endNs", last);
                c.put("meanIntervalMs", Math.round(meanMs * 1000.0) / 1000.0);
                c.put("expectedRateMs", expectedMs);
                c.put("segmentIndex", segIndex++);
                c.put("boundaryReason", b > 0 ? reasons.get(b - 1) : "stream-start");
                c.put("status", "CANDIDATE");
                out.add(c);
                if (b > 0) {
                    Map<String, Object> gap = new LinkedHashMap<>();
                    WordRec prev = ws.get(start - 1);
                    WordRec next = ws.get(start);
                    gap.put("id", "gap:" + prev.id() + "->" + next.id());
                    gap.put("kind", "acquisition-gap");
                    gap.put("stream", e.getKey());
                    gap.put("fromWordId", prev.id());
                    gap.put("toWordId", next.id());
                    gap.put("gapNs", next.rxTimeNs() - prev.rxTimeNs());
                    gap.put("unit", "ns");
                    gap.put("status", "CANDIDATE");
                    out.add(gap);
                }
                start = end;
            }
        }
        return out;
    }

    private static long median(List<Long> values) {
        List<Long> copy = new ArrayList<>(values);
        copy.sort(Long::compareTo);
        return copy.get(copy.size() / 2);
    }
}
