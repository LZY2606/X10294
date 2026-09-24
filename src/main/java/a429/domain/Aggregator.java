package a429.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engineering-value aggregates. Bad-parity words are decoded and displayed but
 * never enter an aggregate; invalid SSM samples are also held aside. Exclusions
 * are reported, never silently dropped.
 */
public class Aggregator {

    public record Aggregate(
            String key,
            String channelId,
            String canonicalLabel,
            String name,
            String unit,
            String datum,
            String dictVersion,
            int includedCount,
            int parityExcludedCount,
            int stateExcludedCount,
            int unconvertedCount,
            Double min,
            Double max,
            Double mean,
            Map<String, Integer> discreteCounts,
            List<String> includedWordIds,
            List<String> excludedWordIds,
            List<String> excludedReasons
    ) {
    }

    public List<Aggregate> aggregate(List<DecodedWord> rows) {
        Map<String, List<DecodedWord>> groups = new LinkedHashMap<>();
        for (DecodedWord row : rows) {
            Decoded d = row.decoded();
            if (!d.labelHit()) {
                continue;
            }
            String key = row.word().channelId() + "|" + d.labelCanonicalOctal() + "|" + d.dictVersion();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Aggregate> out = new ArrayList<>();
        for (Map.Entry<String, List<DecodedWord>> e : groups.entrySet()) {
            List<DecodedWord> rows2 = e.getValue();
            DecodedWord first = rows2.get(0);
            String channelId = first.word().channelId();
            String label = first.decoded().labelCanonicalOctal();
            String name = first.decoded().entryName();
            String unit = first.decoded().engineering() == null ? ""
                    : first.decoded().engineering().unit();
            String datum = first.decoded().engineering() == null ? ""
                    : first.decoded().engineering().datum();
            String dictVersion = first.decoded().dictVersion();

            List<String> includedIds = new ArrayList<>();
            List<String> excludedIds = new ArrayList<>();
            List<String> excludedReasons = new ArrayList<>();
            int parityExcluded = 0;
            int stateExcluded = 0;
            int unconverted = 0;
            List<Double> numeric = new ArrayList<>();
            Map<String, Integer> discreteCounts = new LinkedHashMap<>();

            for (DecodedWord row : rows2) {
                RawWord w = row.word();
                Decoded d = row.decoded();
                if (!d.parityOk()) {
                    parityExcluded++;
                    excludedIds.add(w.wordId());
                    excludedReasons.add(w.wordId() + ": PARITY_FAIL excluded from aggregate");
                    continue;
                }
                Decoded.EngineeringValue eng = d.engineering();
                if (eng != null && !eng.converted()) {
                    unconverted++;
                    excludedIds.add(w.wordId());
                    excludedReasons.add(w.wordId() + ": UNCONVERTED (diagnostic retained at source position)");
                    continue;
                }
                if (eng != null && !eng.validState()) {
                    stateExcluded++;
                    excludedIds.add(w.wordId());
                    excludedReasons.add(w.wordId() + ": SSM_INVALID state excluded from aggregate");
                    continue;
                }
                includedIds.add(w.wordId());
                if (eng != null && eng.numeric() != null) {
                    numeric.add(eng.numeric());
                } else if (eng != null && eng.text() != null) {
                    discreteCounts.merge(eng.text(), 1, Integer::sum);
                }
            }
            Double min = null;
            Double max = null;
            Double mean = null;
            if (!numeric.isEmpty()) {
                min = numeric.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
                max = numeric.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
                mean = numeric.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                min = round(min);
                max = round(max);
                mean = round(mean);
            }
            out.add(new Aggregate(e.getKey(), channelId, label, name, unit, datum, dictVersion,
                    includedIds.size(), parityExcluded, stateExcluded, unconverted, min, max, mean,
                    discreteCounts, includedIds, excludedIds, excludedReasons));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
