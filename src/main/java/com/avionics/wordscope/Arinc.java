package com.avionics.wordscope;

/** ARINC 429 32位原始字解码。原始位永不修改，解码结果只是视图。 */
public final class Arinc {

    public static final String RULE_VERSION = "rules-1.0";

    private Arinc() {}

    public record Decoded(
            long word,
            String rawHex,
            int labelByte,      // 线上低8位原样
            String labelOctal,  // 八进制展示（不改变底层位）
            int sdi,
            int ssm,
            long dataField,     // 19位数据域
            int parityBit,
            boolean parityOk) {}

    /** 标准位序解码：bit1..8 label(线上先传MSB)，9-10 SDI，11-29 数据，30-31 SSM，32 奇偶(奇校验)。 */
    public static Decoded decode(long word) {
        long w = word & 0xFFFFFFFFL;
        int labelByte = (int) (w & 0xFF);
        int label = reverse8(labelByte);
        int sdi = (int) ((w >> 8) & 0x3);
        long data = (w >> 10) & 0x7FFFFL;
        int ssm = (int) ((w >> 29) & 0x3);
        int parity = (int) ((w >> 31) & 0x1);
        boolean ok = (Long.bitCount(w) & 1) == 1; // 奇校验：含校验位共奇数个1
        return new Decoded(w, String.format("0x%08X", w), labelByte,
                String.format("%03o", label), sdi, ssm, data, parity, ok);
    }

    /** 位序反转视图：整字32位反转后再按标准位序解码（用于位序反转探针的候选）。 */
    public static Decoded decodeReversed(long word) {
        return decode(reverse32(word));
    }

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >> i) & 1);
        }
        return r;
    }

    public static long reverse32(long w) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | ((w >> i) & 1L);
        }
        return r & 0xFFFFFFFFL;
    }

    /** 组字：label为八进制字符串。parityOk=false时故意造奇偶错误字。 */
    public static long buildWord(String labelOctal, int sdi, long data19, int ssm, boolean parityOk) {
        int label = Integer.parseInt(labelOctal, 8);
        long w = ((long) reverse8(label))
                | ((long) (sdi & 0x3) << 8)
                | ((data19 & 0x7FFFFL) << 10)
                | ((long) (ssm & 0x3) << 29);
        int ones = Long.bitCount(w);
        int parity = (ones & 1) == 1 ? 0 : 1; // 凑成奇数个1
        if (!parityOk) {
            parity ^= 1;
        }
        return w | ((long) parity << 31);
    }
}
