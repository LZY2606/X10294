package av.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derived layer: turns a source word plus a dictionary version into an
 * engineering interpretation. Nothing here mutates source data; unconvertible
 * words stay in place with a diagnostic instead of being dropped.
 */
public final class Interpreter {

    public static final String SSM_MEANING = "0=正/有效;1=无计算数据(NCD);2=功能测试;3=负/有效";

    private Interpreter() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> entryOf(Map<String, Object> dict, String labelOctal) {
        if (dict == null) {
            return null;
        }
        for (Object o : (List<Object>) dict.getOrDefault("entries", List.of())) {
            Map<String, Object> e = (Map<String, Object>) o;
            if (labelOctal.equals(String.valueOf(e.get("label")))) {
                return e;
            }
        }
        return null;
    }

    public static List<Integer> ssmValid(Map<String, Object> entry) {
        Object v = entry == null ? null : entry.get("ssmValid");
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return List.of(0, 3);
        }
        List<Integer> out = new ArrayList<>();
        for (Object o : list) {
            out.add(((Number) o).intValue());
        }
        return out;
    }

    /** Interpret one word under the given dictionary version (may be {@code null}). */
    public static Map<String, Object> interpret(WordRec w, Map<String, Object> dict) {
        String version = dict == null ? null : String.valueOf(dict.get("version"));
        Map<String, Object> entry = entryOf(dict, w.labelOctal());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wordId", w.id());
        out.put("seq", w.seq());
        out.put("channel", w.channel());
        out.put("labelOctal", w.labelOctal());
        out.put("labelBits", w.labelBits());
        out.put("sdi", w.sdi());
        out.put("ssm", w.ssm());
        out.put("ssmMeaning", SSM_MEANING);
        out.put("dataFieldHex", String.format("0x%05X", w.dataField()));
        out.put("parityOk", w.parityOk());
        out.put("aggregatable", false);

        if (!w.parityOk()) {
            out.put("status", "EXCLUDED_BAD_PARITY");
            out.put("value", null);
            out.put("valueUnit", null);
            out.put("diagnostic", "奇偶校验失败：该字不进入聚合值，原位保留");
            return out;
        }

        if (entry == null) {
            Map<String, Object> reversedHint = reversalCandidate(w, dict);
            out.put("status", reversedHint != null ? "CANDIDATE_BIT_ORDER_REVERSED" : "NO_DICT_ENTRY");
            out.put("value", null);
            out.put("valueUnit", null);
            out.put("diagnostic", reversedHint != null
                    ? "当前字序下标签未命中字典；全字位序反转后可命中，仅作为候选解释保留"
                    : "标签在该字典版本中无定义，原始字保留不转换");
            if (reversedHint != null) {
                out.put("candidate", reversedHint);
            }
            return out;
        }

        String encoding = String.valueOf(entry.get("encoding"));
        out.put("encoding", encoding);
        out.put("dictHit", dictHit(entry, version));

        if (!ssmValid(entry).contains(w.ssm())) {
            out.put("status", "DIAGNOSTIC_SSM_INVALID");
            out.put("value", null);
            out.put("valueUnit", String.valueOf(entry.getOrDefault("unit", "")));
            out.put("diagnostic", "SSM=" + w.ssm() + " 不是该量允许状态 " + ssmValid(entry)
                    + "，工程值不成立，原始字保留");
            return out;
        }

        switch (encoding) {
            case "BNR" -> decodeBnr(w, entry, out);
            case "BCD" -> decodeBcd(w, entry, out);
            case "DISCRETE" -> decodeDiscrete(w, entry, out);
            default -> {
                out.put("status", "NO_DICT_ENTRY");
                out.put("value", null);
                out.put("valueUnit", null);
                out.put("diagnostic", "未知编码 " + encoding);
            }
        }
        return out;
    }

    private static void decodeBnr(WordRec w, Map<String, Object> entry, Map<String, Object> out) {
        double res = ((Number) entry.getOrDefault("resolution", 1)).doubleValue();
        boolean negative = w.ssm() == 3;
        double value = (negative ? -1.0 : 1.0) * w.dataField() * res;
        out.put("status", "OK");
        out.put("value", value);
        out.put("valueUnit", String.valueOf(entry.getOrDefault("unit", "")));
        out.put("resolution", res);
        out.put("aggregatable", true);
        out.put("coordinateBasis", entry.getOrDefault("coordinateBasis", "ARINC429 BNR 符号-幅值"));
    }

    private static void decodeBcd(WordRec w, Map<String, Object> entry, Map<String, Object> out) {
        long data = w.dataField();
        List<Integer> digits = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int nib = (int) ((data >>> (4 * i)) & 0xF);
            digits.add(nib);
            if (nib > 9) {
                bad.add("第" + i + "位(低起)=" + String.format("0x%X", nib));
            }
        }
        int high = (int) ((data >>> 16) & 0x7);
        digits.add(high);
        if (!bad.isEmpty()) {
            out.put("status", "DIAGNOSTIC_BCD_ILLEGAL");
            out.put("value", null);
            out.put("valueUnit", String.valueOf(entry.getOrDefault("unit", "")));
            out.put("bcdDigitsLowToHigh", digits);
            out.put("diagnostic", "BCD 非法数字：" + String.join(", ", bad)
                    + "；不做工程值转换，数据域保留在原位");
            return;
        }
        long magnitude = 0;
        for (int i = digits.size() - 1; i >= 0; i--) {
            magnitude = magnitude * 10 + digits.get(i);
        }
        double res = ((Number) entry.getOrDefault("resolution", 1)).doubleValue();
        out.put("status", "OK");
        out.put("value", magnitude * res);
        out.put("valueUnit", String.valueOf(entry.getOrDefault("unit", "")));
        out.put("bcdDigitsLowToHigh", digits);
        out.put("resolution", res);
        out.put("aggregatable", true);
    }

    @SuppressWarnings("unchecked")
    private static void decodeDiscrete(WordRec w, Map<String, Object> entry, Map<String, Object> out) {
        Map<String, Object> bitDefs = (Map<String, Object>) entry.getOrDefault("bits", Map.of());
        Map<String, Object> states = new LinkedHashMap<>();
        for (Map.Entry<String, Object> def : bitDefs.entrySet()) {
            int bit = Integer.parseInt(def.getKey());
            boolean set = ((w.word() >>> bit) & 1L) == 1L;
            states.put("bit" + bit, Map.of("meaning", def.getValue(), "set", set));
        }
        out.put("status", "OK");
        out.put("value", states);
        out.put("valueUnit", "discrete-state");
        out.put("aggregatable", false);
    }

    /** Candidate interpretation: only offered when the reversed word resolves in the same dictionary. */
    private static Map<String, Object> reversalCandidate(WordRec w, Map<String, Object> dict) {
        long reversed = Arinc.reverseBits32(w.word());
        String label = Arinc.labelOctal(reversed);
        if (entryOf(dict, label) == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidateId", "bitrev:" + w.id());
        out.put("rawHex", String.format("0x%08X", reversed));
        out.put("labelOctal", label);
        out.put("sdi", Arinc.sdi(reversed));
        out.put("ssm", Arinc.ssm(reversed));
        out.put("dataFieldHex", String.format("0x%05X", Arinc.dataField(reversed)));
        out.put("parityOk", Arinc.oddParityOk(reversed));
        out.put("note", "全字位序反转候选，仅作为候选解释，不改原位数据");
        return out;
    }

    private static Map<String, Object> dictHit(Map<String, Object> entry, String version) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("dictionaryVersion", version);
        hit.put("entryId", entry.getOrDefault("id", entry.get("label")));
        hit.put("label", String.valueOf(entry.get("label")));
        hit.put("encoding", entry.get("encoding"));
        return hit;
    }
}
