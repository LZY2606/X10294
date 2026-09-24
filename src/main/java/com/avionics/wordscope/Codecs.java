package com.avionics.wordscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 工程值换算：BNR / BCD / discrete。无法转换的值保留原表示并附诊断。 */
public final class Codecs {

    private Codecs() {}

    public record Value(String display, Double numeric, String unit, String status, String diagnostic) {
        public static Value ok(String display, double numeric, String unit) {
            return new Value(display, numeric, unit, "OK", null);
        }
        public static Value flagged(String display, Double numeric, String unit, String status, String diagnostic) {
            return new Value(display, numeric, unit, status, diagnostic);
        }
    }

    /** BNR：19位数据域，可选符号(最高位为符号位，补码)，乘分辨率，附单位。 */
    public static Value decodeBnr(long data19, double resolution, String unit, boolean signed) {
        long raw = data19 & 0x7FFFFL;
        double v;
        if (signed && (raw & 0x40000L) != 0) {
            v = (raw - 0x80000L) * resolution;
        } else {
            v = raw * resolution;
        }
        return Value.ok(trim(v) + " " + unit, v, unit);
    }

    /** BCD：从低位起每4位一个十进制数字；A-F为非法数字，保留原表示并附诊断。 */
    public static Value decodeBcd(long data19, int digits, String unit) {
        StringBuilder disp = new StringBuilder();
        long value = 0;
        for (int i = digits - 1; i >= 0; i--) {
            int nib = (int) ((data19 >> (4 * i)) & 0xF);
            if (nib > 9) {
                return Value.flagged(
                        String.format("0x%05X", data19) + " (原样保留)", null, unit,
                        "DIAGNOSTIC", "BCD_ILLEGAL_DIGIT nibble=" + Integer.toHexString(nib).toUpperCase());
            }
            disp.append(nib);
            value = value * 10 + nib;
        }
        return Value.ok(disp + " " + unit, (double) value, unit);
    }

    /** Discrete：按字典位名展开。 */
    public static Value decodeDiscrete(long data19, Map<Integer, String> bitNames) {
        List<String> active = new ArrayList<>();
        for (Map.Entry<Integer, String> e : bitNames.entrySet()) {
            int bit = e.getKey();
            if (bit >= 11 && bit <= 29 && ((data19 >> (bit - 11)) & 1) == 1) {
                active.add(e.getValue());
            }
        }
        String disp = active.isEmpty() ? "(none)" : String.join(",", active);
        return Value.ok(disp, null, "1");
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
