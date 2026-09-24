package avio.a429.core;

import avio.a429.core.Model.Candidate;
import avio.a429.core.Model.Derived;
import avio.a429.core.Model.InterpretResult;
import avio.a429.core.Model.RawWord;
import avio.a429.core.Model.RuleBasis;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure interpretation pass: raw words + pinned dictionary version + rule version
 * -> derived values, retained candidates and machine-readable rule bases.
 * Never mutates raw input; ambiguity produces multiple candidates, not a choice.
 */
public final class Interpreter {
    public static final String RULE_VERSION = "rules-1.0";
    private static final ObjectMapper M = new ObjectMapper();

    private Interpreter() {}

    public static InterpretResult run(String branchId, List<RawWord> words, Dictionary dict) {
        List<Derived> derived = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        List<RuleBasis> bases = List.of(
                new RuleBasis("octal-display", "label octal display never changes underlying bits", "label"),
                new RuleBasis("dict-typed-decode", "BNR/BCD/discrete interpretation is decided by dictionary version " + dict.version, "value"),
                new RuleBasis("segment-candidates", "rate changes and acquisition gaps are kept as segment candidates", "rate"),
                new RuleBasis("parity-exclusion", "words with bad parity never enter aggregated values", "aggregate"));

        Map<String, List<RawWord>> byChannel = new LinkedHashMap<>();
        for (RawWord w : words) {
            byChannel.computeIfAbsent(w.channel(), k -> new ArrayList<>()).add(w);
        }

        for (var entry : byChannel.entrySet()) {
            List<RawWord> ws = entry.getValue();
            // per-word decode
            for (RawWord w : ws) {
                decodeWord(branchId, w, dict, derived, candidates);
            }
            // rate segmentation + aggregates
            segment(branchId, ws, dict, derived, candidates);
        }
        return new InterpretResult(derived, candidates, bases);
    }

    private static void decodeWord(String branchId, RawWord w, Dictionary dict,
                                   List<Derived> derived, List<Candidate> candidates) {
        long word = w.word();
        List<String> deps = deps(w, dict);
        int labelOct = Arinc.labelOctal(word);
        String octal = String.format("%03o", labelOct);
        var dictEntry = dict.lookup(labelOct);

        ObjectNode labelPayload = M.createObjectNode();
        labelPayload.put("rawHex", w.rawHex());
        labelPayload.put("labelByteRaw", String.format("0x%02X", Arinc.labelByteRaw(word)));
        labelPayload.put("octal", octal);
        labelPayload.put("declaredLabel", w.labelText());
        labelPayload.put("basis", "octal display is a view of bits 1-8 after spec reversal; bits unchanged");
        String labelDiag;
        if (dictEntry.isPresent()) {
            labelDiag = "DICT_HIT " + dictEntry.get().name() + " @" + dict.version;
            labelPayload.put("dictEntry", dictEntry.get().name());
        } else {
            labelDiag = "DICT_MISS @" + dict.version;
            // legal alternative: maybe the byte was stored without the spec reversal
            var alt = dict.lookup(Arinc.labelByteRaw(word));
            if (alt.isPresent()) {
                ObjectNode p = M.createObjectNode();
                p.put("decodedLabel", octal);
                p.put("reversedBitsLabel", String.format("%03o", Arinc.labelByteRaw(word)));
                p.put("dictHitIfReversed", alt.get().name());
                p.put("basis", "bit-order reversal of bits 1-8 yields a dictionary hit; both readings retained");
                candidates.add(new Candidate(uid(), branchId, "bit_order", w.id(), p.toString()));
                labelDiag += " | ALT bit_order candidate retained";
            }
        }
        if (w.labelText() != null && !w.labelText().isBlank() && !w.labelText().equals(octal)) {
            labelDiag += " | DECLARED_LABEL_DIFFERS imported=" + w.labelText();
        }
        derived.add(new Derived(uid(), branchId, w.id(), "label", null, octal, null, null,
                labelDiag, dict.version, RULE_VERSION, labelPayload.toString(), deps));

        // status: parity + SSM, kept verbatim even when invalid
        ObjectNode statusPayload = M.createObjectNode();
        statusPayload.put("parityOk", Arinc.parityOk(word));
        statusPayload.put("ssm", Arinc.ssm(word));
        statusPayload.put("ssmText", Arinc.ssmText(Arinc.ssm(word)));
        statusPayload.put("aggregatable", Arinc.aggregatable(word));
        String statusDiag = (Arinc.parityOk(word) ? "PARITY_OK" : "PARITY_BAD excluded from aggregates")
                + " | SSM_" + Arinc.ssmText(Arinc.ssm(word));
        derived.add(new Derived(uid(), branchId, w.id(), "status", null,
                Arinc.ssmText(Arinc.ssm(word)), null, null, statusDiag,
                dict.version, RULE_VERSION, statusPayload.toString(), deps));

        // typed value per dictionary
        if (dictEntry.isEmpty()) {
            derived.add(new Derived(uid(), branchId, w.id(), "value", null, null, null, null,
                    "NO_DICT_ENTRY value left uninterpreted at source position",
                    dict.version, RULE_VERSION, "{}", deps));
            return;
        }
        var e = dictEntry.get();
        switch (e.type()) {
            case "BNR" -> {
                long signed = Arinc.bnrSigned(word);
                double eng = signed * e.scale() + e.offset();
                ObjectNode p = M.createObjectNode();
                p.put("signedRaw", signed);
                p.put("signBit", Arinc.bnrSign(word));
                p.put("scale", e.scale());
                p.put("offset", e.offset());
                p.put("dictVersion", dict.version);
                derived.add(new Derived(uid(), branchId, w.id(), "value", eng,
                        null, e.unit(), e.frame(),
                        signed < 0 ? "BNR_NEGATIVE two's complement" : null,
                        dict.version, RULE_VERSION, p.toString(), deps));
            }
            case "BCD" -> {
                int[] d = Arinc.bcdDigits(word);
                int bad = -1;
                for (int i = 0; i < d.length; i++) {
                    if (d[i] > 9) { bad = i; break; }
                }
                ObjectNode p = M.createObjectNode();
                p.put("digits", new int[]{d[3], d[2], d[1], d[0]});
                if (bad >= 0) {
                    p.put("illegalNibble", d[bad]);
                    p.put("nibbleIndex", bad);
                    derived.add(new Derived(uid(), branchId, w.id(), "value", null,
                            null, e.unit(), e.frame(),
                            "BCD_ILLEGAL_DIGIT nibble=0x" + Integer.toHexString(d[bad]).toUpperCase()
                                    + " value kept at source position, not plotted",
                            dict.version, RULE_VERSION, p.toString(), deps));
                } else {
                    long v = d[0] + d[1] * 10L + d[2] * 100L + d[3] * 1000L;
                    double eng = v * e.scale() + e.offset();
                    derived.add(new Derived(uid(), branchId, w.id(), "value", eng,
                            null, e.unit(), e.frame(), null,
                            dict.version, RULE_VERSION, p.toString(), deps));
                }
            }
            case "DISCRETE" -> {
                long bits = (word >> 10) & 0x7FFFFL;
                String bitStr = String.format("%19s", Long.toBinaryString(bits)).replace(' ', '0');
                ObjectNode p = M.createObjectNode();
                p.put("bits", bitStr);
                derived.add(new Derived(uid(), branchId, w.id(), "value", null,
                        bitStr, e.unit(), e.frame(), null,
                        dict.version, RULE_VERSION, p.toString(), deps));
            }
            default -> derived.add(new Derived(uid(), branchId, w.id(), "value", null, null,
                    null, null, "UNKNOWN_DICT_TYPE " + e.type(), dict.version, RULE_VERSION, "{}", deps));
        }
    }

    /** Gap-driven segmentation: every suspicious gap yields BOTH legal candidates. */
    private static void segment(String branchId, List<RawWord> ws, Dictionary dict,
                                List<Derived> derived, List<Candidate> candidates) {
        if (ws.size() < 2) return;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < ws.size(); i++) {
            gaps.add(ws.get(i).rxNs() - ws.get(i - 1).rxNs());
        }
        long median = median(gaps);
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        for (int i = 0; i < gaps.size(); i++) {
            long g = gaps.get(i);
            RawWord at = ws.get(i + 1);
            if (g > median * 1.5) {
                boundaries.add(i + 1);
                ObjectNode gap = M.createObjectNode();
                gap.put("gapNs", g);
                gap.put("medianNs", median);
                gap.put("prevWordId", ws.get(i).id());
                gap.put("basis", "gap exceeds 1.5x local median; acquisition gap and rate switch are both legal");
                candidates.add(new Candidate(uid(), branchId, "acquisition_gap", at.id(), gap.toString()));
                ObjectNode sw = M.createObjectNode();
                sw.put("gapNs", g);
                sw.put("medianNs", median);
                sw.put("prevWordId", ws.get(i).id());
                sw.put("basis", "gap may mark a rate switch boundary; retained until adjudicated");
                candidates.add(new Candidate(uid(), branchId, "rate_switch", at.id(), sw.toString()));
            } else if (g < median * 0.67 && i + 1 < gaps.size() && gaps.get(i + 1) < median * 0.67) {
                boundaries.add(i + 1);
                ObjectNode sw = M.createObjectNode();
                sw.put("gapNs", g);
                sw.put("medianNs", median);
                sw.put("basis", "sustained shorter interval indicates rate increase");
                candidates.add(new Candidate(uid(), branchId, "rate_switch", at.id(), sw.toString()));
            }
        }
        boundaries.add(ws.size());
        // aggregates per segment per label; bad-parity / non-normal SSM words excluded
        for (int b = 0; b + 1 < boundaries.size(); b++) {
            Map<String, List<RawWord>> byLabel = new LinkedHashMap<>();
            for (int i = boundaries.get(b); i < boundaries.get(b + 1); i++) {
                RawWord w = ws.get(i);
                byLabel.computeIfAbsent(Arinc.labelOctalText(w.word()), k -> new ArrayList<>()).add(w);
            }
            for (var le : byLabel.entrySet()) {
                var dictEntry = dict.lookup(Integer.parseInt(le.getKey(), 8));
                if (dictEntry.isEmpty() || !dictEntry.get().type().equals("BNR")) continue;
                double sum = 0;
                int n = 0;
                List<String> memberIds = new ArrayList<>();
                List<String> excluded = new ArrayList<>();
                for (RawWord w : le.getValue()) {
                    if (Arinc.aggregatable(w.word())) {
                        sum += Arinc.bnrSigned(w.word()) * dictEntry.get().scale() + dictEntry.get().offset();
                        n++;
                        memberIds.add(w.id());
                    } else {
                        excluded.add(w.id() + ":" + (Arinc.parityOk(w.word()) ? "SSM_" + Arinc.ssmText(Arinc.ssm(w.word())) : "PARITY_BAD"));
                    }
                }
                if (n == 0) continue;
                ObjectNode p = M.createObjectNode();
                p.put("segment", b);
                p.put("label", le.getKey());
                p.put("members", memberIds);
                p.put("excluded", excluded);
                p.put("basis", "mean of aggregatable words only; bad parity words excluded");
                List<String> deps = new ArrayList<>(memberIds);
                deps.addAll(excluded.stream().map(s -> s.split(":")[0]).toList());
                deps.add("DICT:" + dict.version);
                deps.add("RULE:" + RULE_VERSION);
                derived.add(new Derived(uid(), branchId, memberIds.get(0), "aggregate",
                        sum / n, null, dictEntry.get().unit(), dictEntry.get().frame(),
                        excluded.isEmpty() ? null : "EXCLUDED " + String.join(",", excluded),
                        dict.version, RULE_VERSION, p.toString(), deps));
            }
        }
    }

    private static List<String> deps(RawWord w, Dictionary dict) {
        return List.of(w.id(), "DICT:" + dict.version, "RULE:" + RULE_VERSION);
    }

    private static long median(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        s.sort(Long::compareTo);
        return s.get(s.size() / 2);
    }

    public static String uid() {
        return UUID.randomUUID().toString();
    }
}
