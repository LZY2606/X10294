package com.avionics.a429.core;

import com.avionics.a429.core.Models.Aggregate;
import com.avionics.a429.core.Models.DecodedWord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工程值聚合。规则（机器可读地写入结果）：
 * 坏奇偶字不进入聚合值；SSM 非 NORMAL 与不可转换值同样排除，
 * 全部被排除的字保留在 excluded 清单中并附原因。
 */
public final class Aggregator {
    private Aggregator() {}

    public static List<Aggregate> aggregate(List<DecodedWord> words) {
        Map<String, List<DecodedWord>> groups = new LinkedHashMap<>();
        for (DecodedWord w : words) {
            if (!w.dictHit() || w.engUnit() == null) continue;
            groups.computeIfAbsent(w.channel() + "|" + w.labelOctal() + "|" + w.sdi(),
                    k -> new ArrayList<>()).add(w);
        }
        List<Aggregate> out = new ArrayList<>();
        for (Map.Entry<String, List<DecodedWord>> e : groups.entrySet()) {
            String[] key = e.getKey().split("\\|");
            List<DecodedWord> ws = e.getValue();
            List<String> included = new ArrayList<>();
            List<Map<String, String>> excluded = new ArrayList<>();
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
            int count = 0;
            String unit = null, name = null;
            for (DecodedWord w : ws) {
                unit = w.engUnit();
                if (w.aggregateEligible() && w.engValue() != null) {
                    included.add(w.uid());
                    double v = w.engValue();
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                    sum += v;
                    count++;
                } else {
                    excluded.add(Map.of("uid", w.uid(),
                            "reason", w.excludeReason() == null ? "EXCLUDED" : w.excludeReason()));
                }
            }
            String uid = "agg:" + key[0] + ":" + key[1] + ":" + key[2];
            out.add(new Aggregate(uid, key[0], key[1], Integer.parseInt(key[2]),
                    name, unit, count,
                    count > 0 ? min : null, count > 0 ? max : null,
                    count > 0 ? sum / count : null,
                    List.copyOf(included), List.copyOf(excluded)));
        }
        return out;
    }
}
