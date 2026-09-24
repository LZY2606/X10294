package arinc429.service;

import arinc429.domain.Models.Aggregate;
import arinc429.domain.Models.DecodedWord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per channel/label engineering aggregates; bad parity never enters values. */
public final class AggregateService {

    public List<Aggregate> aggregate(String branchId, List<DecodedWord> words) {
        Map<String, List<DecodedWord>> groups = new LinkedHashMap<>();
        for (DecodedWord w : words) {
            if ("UNKNOWN".equals(w.encoding())) {
                continue;
            }
            groups.computeIfAbsent(w.channelId(), k -> new ArrayList<>());
        }
        List<Aggregate> out = new ArrayList<>();
        for (Map.Entry<String, List<DecodedWord>> e : groups.entrySet()) {
            String[] parts = e.getKey().split("/", 2);
            List<DecodedWord> group = e.getValue();
            int badParity = 0;
            int badSsm = 0;
            List<Double> numeric = new ArrayList<>();
            Double last = null;
            for (DecodedWord w : group) {
                if (!w.parityOk()) {
                    badParity++;
                    continue;
                }
                boolean ssmOk = w.diagnostic() == null || !w.diagnostic().contains("SSM_");
                if (!ssmOk) {
                    badSsm++;
                    continue;
                }
                if (w.engineering() != null) {
                    numeric.add(w.engineering());
                    last = w.engineering();
                }
            }
            String encoding = group.get(0).encoding();
            String units = group.get(0).units();
            String dict = group.get(0).dictionaryVersion();
            Aggregate a = new Aggregate(
                    Ids.aggregateId(branchId, parts[0], parts[1]),
                    branchId, parts[0], parts[1], encoding, dict,
                    group.size(), badParity, badSsm,
                    numeric.isEmpty() ? null : last, units,
                    numeric.stream().min(Double::compare).orElse(null),
                    numeric.stream().max(Double::compare).orElse(null),
                    false);
            out.add(a);
        }
        return out;
    }
}
