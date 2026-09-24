package avionics.core;

/**
 * 数据域译码：BNR（二进制补码）、BCD（非法数字留诊断）、DISCRETE。
 * 坏奇偶字不进入聚合；SSM 无效状态（NCD/TEST）同样只留诊断。
 * 无法转换的值留在原位置并附诊断，不丢弃。
 */
public final class Decoder {

    public enum Status { NORMAL, BAD_PARITY, NCD, TEST, ILLEGAL_BCD, NO_DICT_ENTRY }

    public record Decoded(Status status, Double engValue, String unit,
                          String diagnostic, String encoding) {
        public boolean aggregatable() { return status == Status.NORMAL; }
    }

    private Decoder() {}

    public static Decoded decode(Arinc429.Parsed word, Dictionary dict) {
        if (!word.parityOk()) {
            return new Decoded(Status.BAD_PARITY, null, null,
                    "奇偶校验失败：字保留在原位，不进入聚合值", null);
        }
        Dictionary.Entry e = dict.lookup(word.labelOctal());
        if (e == null) {
            return new Decoded(Status.NO_DICT_ENTRY, null, null,
                    "字典 " + dict.dictId + " v" + dict.version + " 未命中 label " + word.labelOctal(), null);
        }
        String ssmMeaning = e.ssmMeaning() == null ? null : e.ssmMeaning().get(word.ssm());
        if ("NCD".equals(ssmMeaning)) {
            return new Decoded(Status.NCD, null, e.unit(),
                    "SSM=" + word.ssm() + " 无计算数据（NCD），值留原位", e.encoding().name());
        }
        if ("TEST".equals(ssmMeaning)) {
            return new Decoded(Status.TEST, null, e.unit(),
                    "SSM=" + word.ssm() + " 功能测试状态，不进入聚合值", e.encoding().name());
        }
        return switch (e.encoding()) {
            case BNR -> decodeBnr(word, e);
            case BCD -> decodeBcd(word, e);
            case DISCRETE -> decodeDiscrete(word, e);
        };
    }

    private static Decoded decodeBnr(Arinc429.Parsed w, Dictionary.Entry e) {
        long raw19 = w.dataField();
        long signed = (raw19 & 0x40000L) != 0 ? raw19 - 0x80000L : raw19; // 19位补码，支持BNR负值
        double v = signed * e.scale() + e.offset();
        return new Decoded(Status.NORMAL, v, e.unit(), null, "BNR");
    }

    private static Decoded decodeBcd(Arinc429.Parsed w, Dictionary.Entry e) {
        long data = w.dataField();
        int[] nibbles = new int[5];
        for (int i = 0; i < 5; i++) nibbles[i] = (int) ((data >>> (4 * (4 - i))) & 0xF);
        StringBuilder digits = new StringBuilder();
        for (int n : nibbles) {
            if (n > 9) {
                return new Decoded(Status.ILLEGAL_BCD, null, e.unit(),
                        "BCD 非法数字 0x" + Integer.toHexString(n).toUpperCase()
                                + "：值不转换，原始数据域 0x" + Long.toHexString(data).toUpperCase() + " 留原位",
                        "BCD");
            }
            digits.append(n);
        }
        double v = Long.parseLong(digits.toString()) * e.scale() + e.offset();
        return new Decoded(Status.NORMAL, v, e.unit(), null, "BCD");
    }

    private static Decoded decodeDiscrete(Arinc429.Parsed w, Dictionary.Entry e) {
        long bits = w.dataField();
        return new Decoded(Status.NORMAL, (double) bits, e.unit(),
                "discrete 位图 0b" + Long.toBinaryString(bits), "DISCRETE");
    }
}
