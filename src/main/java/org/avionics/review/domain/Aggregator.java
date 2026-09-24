package org.avionics.review.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates engineering values per label.
 *
 * <p>Bad parity words never enter an aggregate (their bits are unreliable).
 * SSM-invalid and BCD-illegal words are visible but excluded too. When a
 * dictionary version or convention has been adjudicated for a word, only the
 * chosen engineering value counts.</p>
 */
public final class Aggregator {

    private Aggregator() {
    }

    public record Choice(String convention, String dictVersion) {
    }

    public static List<AggregateValue> aggregate(List<RawWord> words,
                                                 Map<String, List<EngineeringValue>> valuesByWord,
                                                 Map<String, Choice> choices,
                                                 Map<String, String> rejectedEv) {
        record Acc(String label, String dict, String conv, String name, String unit,
                   double sum, int count, int badParity, int ssm, int bcd) {
        }
        Map<String, Acc> accs = new LinkedHashMap<>();
        for (RawWord w : words) {
            List<EngineeringValue> values = valuesByWord.getOrDefault(w.sid(), List.of());
            Map<String, String> rejects = new LinkedHashMap<>();
            for (EngineeringValue ev : values) {
                String key = ev.labelOctal() + "|" + ev.dictVersion() + "|" + ev.convention();
                Acc acc = accs.computeIfAbsent(key, k -> new Acc(
                        ev.labelOctal(), ev.dictVersion(), ev.convention(), ev.name(), ev.unit(),
                        0, 0, 0, 0, 0));
                boolean badParity = ev.diagnostics().contains("BAD_PARITY");
                boolean ssmBad = ev.status().equals("SSM_INVALID");
                boolean bcdBad = ev.status().equals("BCD_ILLEGAL");
                if (badParity) {
                    accs.put(key, new Acc(acc.label, acc.dict, acc.conv, acc.name, acc.unit,
                            acc.sum, acc.count, acc.badParity + 1, acc.ssm, acc.bcd));
                } else if (ssmBad) {
                    accs.put(key, new Acc(acc.label, acc.dict, acc.conv, acc.name, acc.unit,
                            acc.sum, acc.count, acc.badParity, acc.ssm + 1, acc.bcd));
                } else if (bcdBad) {
                    accs.put(key, new Acc(acc.label, acc.dict, acc.conv, acc.name, acc.unit,
                            acc.sum, acc.count, acc.badParity, acc.ssm, acc.bcd + 1));
                } else if (ev.validForAggregate()) {
                    accs.put(key, new Acc(acc.label, acc.dict, acc.conv, acc.name, acc.unit,
                            acc.sum + ev.numeric(), acc.count + 1,
                            acc.badParity, acc.ssm, acc.bcd));
                }
            }
        }
        List<AggregateValue> result = new ArrayList<>();
        for (Map.Entry<String, Acc> e : accs.entrySet()) {
            Acc a = e.getValue();
            double mean = a.count == 0 ? 0 : Math.round((a.sum / a.count) * 1e6d) / 1e6d;
            result.add(new AggregateValue(
                    "agg:" + a.label + ":" + a.conv + ":" + a.dict,
                    a.label, a.dict, a.conv, a.name, a.unit, a.count,
                    a.badParity, a.ssm, a.bcd, mean));
        }
        return result;
    }
}
