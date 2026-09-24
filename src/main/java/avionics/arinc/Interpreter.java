package avionics.arinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派生层解释器：原始表示在导入时保留，这里才做规范化（位序、奇偶、BNR/BCD/discrete）。
 * 无法转换的值保留原位置并附诊断，不丢弃。
 */
public final class Interpreter {
    public static final String RULE_VERSION = "rule-v1";

    public record Diag(String code, String message) {}

    public record Result(Double value, String unit, String display, String basis, List<Diag> diags) {
        public boolean convertible() { return value != null || display != null; }
    }

    private Interpreter() {}

    /** 位序规范化：reversed 记录先反转 32 位再解析，原始 raw 仍保留在 word 行内。 */
    public static long normalize(long raw, String bitOrder) {
        return "reversed".equals(bitOrder) ? ArincWord.reverse32(raw) : raw;
    }

    public static Result interpret(long raw, String bitOrder, Dictionary.Entry entry, String dictId) {
        long word = normalize(raw, bitOrder);
        List<Diag> diags = new ArrayList<>();
        String basis = "dict:" + dictId + "#" + entry.labelOctal() + "/" + entry.type()
                + ("reversed".equals(bitOrder) ? " normalized:bit-reversal" : "");
        if (!ArincWord.parityOk(word)) {
            diags.add(new Diag("parity-bad", "奇偶校验失败：该字不进入聚合值"));
        }
        int ssm = ArincWord.ssm(word);
        int data = ArincWord.data19(word);
        switch (entry.type()) {
            case "BNR" -> {
                if (ssm == 0) diags.add(new Diag("ssm-invalid", "SSM=0 无效状态：值保留但不进入聚合"));
                int signed = (data & 0x40000) != 0 ? data - 0x80000 : data; // 19 位二进制补码，支持 BNR 负值
                double v = signed * entry.resolution() + entry.offset();
                return new Result(v, entry.unit(), trim(v) + " " + entry.unit(), basis, diags);
            }
            case "BCD" -> {
                int digits = entry.digits();
                int[] d = new int[digits];
                for (int i = 0; i < digits; i++) {
                    d[i] = (data >> (4 * (digits - 1 - i))) & 0xF;
                }
                StringBuilder raw = new StringBuilder();
                for (int x : d) raw.append(Integer.toHexString(x).toUpperCase());
                for (int i = 0; i < digits; i++) {
                    if (d[i] > 9) {
                        diags.add(new Diag("bcd-illegal-digit",
                                "BCD 非法数字 0x" + Integer.toHexString(d[i]).toUpperCase()
                                        + "（第 " + (i + 1) + " 位）：保留原始表示，不做数值换算"));
                        return new Result(null, entry.unit(), "RAW 0x" + raw + " (未换算)", basis, diags);
                    }
                }
                long num = 0;
                for (int x : d) num = num * 10 + x;
                double v = num * entry.scale();
                return new Result(v, entry.unit(), trim(v) + " " + entry.unit(), basis, diags);
            }
            case "DISCRETE" -> {
                Map<String, String> flags = new LinkedHashMap<>();
                entry.bits().forEach((bit, name) -> {
                    int b = Integer.parseInt(bit);
                    flags.put(name, ((data >> (b - 11)) & 1) == 1 ? "1" : "0");
                });
                StringBuilder sb = new StringBuilder();
                flags.forEach((k, v) -> { if (sb.length() > 0) sb.append(' '); sb.append(k).append('=').append(v); });
                return new Result(null, entry.unit(), sb.toString(), basis, diags);
            }
            default -> {
                diags.add(new Diag("unknown-type", "未知字典类型 " + entry.type()));
                return new Result(null, entry.unit(), "RAW 0x" + ArincWord.hex(word), basis, diags);
            }
        }
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }
}
