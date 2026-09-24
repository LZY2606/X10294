package org.gsb.a429.arinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工程值解释：BNR、BCD、discrete 三类。
 *
 * 全部规则（位范围、分辨率、符号、单位、坐标基准、位命名）都来自字典版本；
 * 本类只执行纯计算。解释失败时不抛掉数据，而是返回带 diagnostics 的结果，原值留在原位。
 */
public final class Engineering {

    private Engineering() {
    }

    /** BNR 二进制补码解释。位范围 lo..hi 按 ARINC 位编号，hi 为 MSB；signed=true 时 hi 为符号位。 */
    public static BnrResult bnr(int word, int lo, int hi, boolean signed, double lsbValue,
                                String unit, String basis) {
        int raw = Arinc.field(word, lo, hi);
        int len = hi - lo + 1;
        Long magnitude;
        boolean negative = false;
        if (signed) {
            boolean sign = ((raw >>> (len - 1)) & 1) == 1;
            if (sign) {
                negative = true;
                raw = raw - (1 << len);
            }
            magnitude = (long) raw;
        } else {
            magnitude = (long) raw;
        }
        double value = magnitude.doubleValue() * lsbValue;
        List<String> diag = new ArrayList<>();
        if (signed && negative) {
            diag.add("bnr-negative-twos-complement");
        }
        return new BnrResult(raw, signed, negative, lsbValue, value, unit, basis, diag);
    }

    /**
     * BCD 解释：bit 11..29 的 19 位按 4 位一组从 LSB 起装 4 个完整数字（bit 27..29 为未用高位）。
     * digits 描述每个 nibble 的名称/单位；遇到 1010..1111 即非法 BCD 数字，记录诊断，且该字
     * 不产生可用数值（留在原位）。
     */
    public static BcdResult bcd(int word, int lo, int hi, List<String> digitNames,
                                String unit, String basis) {
        int nibbleCount = (hi - lo + 1) / 4;
        List<Integer> digits = new ArrayList<>();
        List<String> diag = new ArrayList<>();
        boolean illegal = false;
        StringBuilder numeric = new StringBuilder();
        for (int k = 0; k < nibbleCount; k++) {
            int nibLo = lo + k * 4;
            int nibHi = nibLo + 3;
            int nib = Arinc.field(word, nibLo, nibHi);
            digits.add(nib);
            String name = k < digitNames.size() ? digitNames.get(k) : ("d" + k);
            if (nib > 9) {
                illegal = true;
                diag.add("bcd-illegal-digit-" + name + "=0x" + Integer.toHexString(nib).toUpperCase()
                        + "@bits" + nibLo + "-" + nibHi);
            } else {
                numeric.append(nib);
            }
        }
        Double value = illegal ? null : Double.parseDouble(numeric.toString());
        return new BcdResult(digits, illegal, value, unit, basis, diag);
    }

    /** discrete 解释：按字典里的位命名读取开关量，输出 name -> true/false 与坐标基准。 */
    public static DiscreteResult discrete(int word, Map<Integer, String> bitNames, String basis) {
        Map<String, Boolean> states = new LinkedHashMap<>();
        Map<String, Integer> sourceBits = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : bitNames.entrySet()) {
            boolean on = Arinc.bit(word, e.getKey());
            states.put(e.getValue(), on);
            sourceBits.put(e.getValue(), e.getKey());
        }
        return new DiscreteResult(states, sourceBits, basis, List.of());
    }

    public record BnrResult(int raw, boolean signed, boolean negative, double lsbValue,
                            double value, String unit, String basis, List<String> diagnostics) {
        public String display() {
            return format(value) + " " + unit + (basis == null || basis.isBlank() ? "" : " (" + basis + ")");
        }
    }

    public record BcdResult(List<Integer> nibbles, boolean illegal, Double value, String unit,
                            String basis, List<String> diagnostics) {
        public String display() {
            if (illegal) {
                return "INVALID-BCD " + unit;
            }
            return format(value) + " " + unit + (basis == null || basis.isBlank() ? "" : " (" + basis + ")");
        }
    }

    public record DiscreteResult(Map<String, Boolean> states, Map<String, Integer> sourceBits,
                                 String basis, List<String> diagnostics) {
    }

    private static String format(double v) {
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        return String.format("%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
