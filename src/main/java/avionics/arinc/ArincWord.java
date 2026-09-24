package avionics.arinc;

/**
 * ARINC 429 32 位原始字工具。
 * 规则：label 的八进制展示不改变底层位 —— 八进制只是 bits 1-8 按传输序反转后的展示形式。
 */
public final class ArincWord {
    private ArincWord() {}

    public static long parseHex(String hex) {
        return Long.parseLong(hex.replace("0x", ""), 16) & 0xFFFFFFFFL;
    }

    public static String hex(long word) {
        return String.format("%08X", word & 0xFFFFFFFFL);
    }

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) r = (r << 1) | ((b >> i) & 1);
        return r;
    }

    public static long reverse32(long w) {
        long r = 0;
        for (int i = 0; i < 32; i++) r = (r << 1) | ((w >> i) & 1L);
        return r & 0xFFFFFFFFL;
    }

    /** label 字节：bits 1-8 按传输序反转（仅展示层，不改底层位）。 */
    public static int labelByte(long word) {
        return reverse8((int) (word & 0xFF));
    }

    public static String labelOctal(long word) {
        return String.format("%03o", labelByte(word));
    }

    public static int sdi(long word) {
        return (int) ((word >> 8) & 0x3);
    }

    /** bits 11-29，19 位数据域。 */
    public static int data19(long word) {
        return (int) ((word >> 10) & 0x7FFFFL);
    }

    public static int ssm(long word) {
        return (int) ((word >> 29) & 0x3);
    }

    /** 奇校验：32 位中 1 的总数应为奇数。 */
    public static boolean parityOk(long word) {
        return (Long.bitCount(word & 0xFFFFFFFFL) & 1) == 1;
    }

    /**
     * 按逻辑域构造 32 位字。
     * @param labelByte label 字节值（如八进制 0204 对应的整数值）
     */
    public static long build(int labelByte, int sdi, int data19, int ssm) {
        long w = reverse8(labelByte & 0xFF);
        w |= ((long) (sdi & 0x3)) << 8;
        w |= ((long) data19 & 0x7FFFFL) << 10;
        w |= ((long) (ssm & 0x3)) << 29;
        if ((Long.bitCount(w) & 1) == 0) w |= 1L << 31; // 奇校验位
        return w & 0xFFFFFFFFL;
    }
}
