package com.avionics.a429.core;

/**
 * ARINC 429 32 位字的位域工具。
 * 位编号采用 LSB0：位 1-8 = label，9-10 = SDI，11-29 = 数据域(19 位)，
 * 30-31 = SSM，32 = 奇偶位（奇校验）。所有 32 位字用 long 低 32 位保存。
 */
public final class ArincWord {
    private ArincWord() {}

    public static final int WORD_BITS = 32;
    /** 高速链路 100 kbit/s，低速 12.5 kbit/s */
    public static final double HIGH_BITS_PER_SEC = 100_000.0;
    public static final double LOW_BITS_PER_SEC = 12_500.0;

    /** 位序反转（通道配置为 REVERSAL 时在派生层做规范化，原始字不动）。 */
    public static long reverseBits(long raw) {
        long r = 0;
        for (int i = 0; i < WORD_BITS; i++) {
            if (((raw >>> i) & 1L) == 1L) r |= (1L << (WORD_BITS - 1 - i));
        }
        return r & 0xFFFF_FFFFL;
    }

    /** 按通道位序配置得到规范化字；原始字保持不变。 */
    public static long normalize(long raw, BitOrder order) {
        long w = raw & 0xFFFF_FFFFL;
        return order == BitOrder.REVERSED ? reverseBits(w) : w;
    }

    /** label 位（位 1-8）。八进制展示只是展示形式，不改变底层位。 */
    public static int labelBits(long w) { return (int) (w & 0xFFL); }

    /** label 的三位八进制展示，如 0o206。 */
    public static String labelOctal(long w) {
        return String.format("%03o", labelBits(w));
    }

    public static int sdi(long w) { return (int) ((w >>> 8) & 0x3L); }

    /** 数据域 19 位（位 11-29）。 */
    public static long dataField(long w) { return (w >>> 10) & 0x7_FFFFL; }

    public static int ssm(long w) { return (int) ((w >>> 29) & 0x3L); }

    public static int parityBit(long w) { return (int) ((w >>> 31) & 1L); }

    /** ARINC 429 奇校验：32 位中 1 的个数应为奇数。 */
    public static boolean parityOk(long w) {
        return Long.bitCount(w & 0xFFFF_FFFFL) % 2 == 1;
    }

    public static String hex(long w) {
        return String.format("0x%08X", w & 0xFFFF_FFFFL);
    }

    public enum BitOrder { NORMAL, REVERSED }
}
