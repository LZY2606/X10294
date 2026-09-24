package a429.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ARINC 429 译码器：从线编号摆放的 32 位字 + 版本化字典得到工程解释。
 * 字典缺失时只给出结构字段，不产生工程值。
 */
public final class Decoder {

    private Decoder() {
    }

    public static DecodeResult decode(long wireWord, Dictionary dictionary) {
        int label = Bits.labelValue(wireWord, false);
        String octal = String.format("%03o", label);
        int sdi = Bits.sdi(wireWord, false);
        int ssmCode = Bits.ssm(wireWord, false);

        Dictionary.LabelDef def = dictionary == null ? null : dictionary.label(octal);
        List<String> diagnostics = new ArrayList<>();
        if (def == null) {
            diagnostics.add("NO_DICTIONARY_HIT:label=" + octal + ",version="
                    + (dictionary == null ? "<none>" : dictionary.version()));
            return new DecodeResult(octal, sdi, ssmCode, Ssm.name(ssmCode, "BNR"), false,
                    null, null, null, null, List.of(), List.of(), diagnostics, false);
        }

        String encoding = def.encoding() == null ? "BNR" : def.encoding().toUpperCase();
        boolean ssmValid = Ssm.operationallyValid(ssmCode, encoding);
        if (!ssmValid) {
            diagnostics.add("SSM_NOT_OPERATIONAL:" + Ssm.name(ssmCode, encoding));
        }

        Double value = null;
        List<DecodeResult.Nibble> nibbles = List.of();
        List<DecodeResult.DiscreteState> discreteStates = List.of();

        switch (encoding) {
            case "BNR" -> value = decodeBnr(wireWord, def, ssmValid, diagnostics);
            case "BCD" -> {
                var bcd = decodeBcd(wireWord, def);
                value = bcd.value;
                nibbles = bcd.nibbles;
                if (bcd.illegal) {
                    diagnostics.add("BCD_ILLEGAL_DIGIT:raw nibble 0xA-0xF retained in place");
                }
            }
            case "DISCRETE" -> {
                discreteStates = decodeDiscrete(wireWord, def);
                value = null; // discrete 不产生单一数值聚合
            }
            default -> diagnostics.add("UNKNOWN_ENCODING:" + encoding);
        }

        return new DecodeResult(octal, sdi, ssmCode, Ssm.name(ssmCode, encoding), ssmValid,
                encoding, value, def.unit(), def.datum(), nibbles, discreteStates,
                diagnostics, true);
    }

    /**
     * BNR：bit29=MSB，bit11=LSB（19 位，分辨率 2^-19 全量程）。有符号时 bit29 为符号位，
     * 按二进制补码解释；工程值 = 原始计数 * resolution。
     */
    static Double decodeBnr(long wireWord, Dictionary.LabelDef def, boolean ssmValid,
                            List<String> diagnostics) {
        long raw19 = Bits.wireField(wireWord, false, 29, 11);
        long count;
        if (def.signed()) {
            if ((raw19 & (1L << 18)) != 0) {
                count = raw19 - (1L << 19);
            } else {
                count = raw19;
            }
        } else {
            count = raw19;
        }
        double resolution = def.resolution() == null ? 1.0 : def.resolution();
        double v = count * resolution;
        if (def.minValue() != null && v < def.minValue()) {
            diagnostics.add(String.format(java.util.Locale.ROOT,
                    "OUT_OF_RANGE:%.6g < min %.6g", v, def.minValue()));
        }
        if (def.maxValue() != null && v > def.maxValue()) {
            diagnostics.add(String.format(java.util.Locale.ROOT,
                    "OUT_OF_RANGE:%.6g > max %.6g", v, def.maxValue()));
        }
        return v;
    }

    record BcdOut(Double value, List<DecodeResult.Nibble> nibbles, boolean illegal) {
    }

    /**
     * BCD：数字从 bit29 向低位排列，每 nibble 4 位；第 5 位（若使用）只取 3 位。
     * 非法 nibble（>=10）保留原始值，整体无法转换时 value=null。
     */
    static BcdOut decodeBcd(long wireWord, Dictionary.LabelDef def) {
        int digits = Math.max(1, Math.min(5, def.bcdDigits() <= 0 ? 5 : def.bcdDigits()));
        List<DecodeResult.Nibble> nibbles = new ArrayList<>();
        boolean illegal = false;
        int hi = 29;
        double accum = 0;
        for (int pos = 0; pos < digits; pos++) {
            int width = (pos == digits - 1 && digits == 5) ? 3 : 4;
            int lo = hi - width + 1;
            int digit = (int) Bits.wireField(wireWord, false, hi, lo);
            boolean legal = digit <= 9;
            if (!legal) {
                illegal = true;
            } else {
                accum = accum * 10 + digit;
            }
            nibbles.add(new DecodeResult.Nibble(pos, hi, lo, digit, legal));
            hi = lo - 1;
        }
        return new BcdOut(illegal ? null : accum, List.copyOf(nibbles), illegal);
    }

    static List<DecodeResult.DiscreteState> decodeDiscrete(long wireWord, Dictionary.LabelDef def) {
        List<DecodeResult.DiscreteState> out = new ArrayList<>();
        if (def.discreteBits() == null) {
            return out;
        }
        for (Dictionary.DiscreteBit db : def.discreteBits()) {
            boolean set = Bits.wireBit(wireWord, false, db.bit()) == 1;
            out.add(new DecodeResult.DiscreteState(db.bit(), db.name(), set,
                    db.zeroMeaning(), db.oneMeaning()));
        }
        return out;
    }

    /** 给页面/事件使用的位域分解（稳定顺序 bit1..bit32）。 */
    public static List<Map<String, Object>> bitFields(long raw, boolean reversed) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int b = 1; b <= 32; b++) {
            String role;
            if (b <= 8) {
                role = "LABEL";
            } else if (b <= 10) {
                role = "SDI";
            } else if (b <= 29) {
                role = "DATA";
            } else if (b <= 31) {
                role = "SSM";
            } else {
                role = "PARITY";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bit", b);
            m.put("value", Bits.wireBit(raw, reversed, b));
            m.put("role", role);
            rows.add(m);
        }
        return rows;
    }
}
