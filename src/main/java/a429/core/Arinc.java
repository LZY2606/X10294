package a429.core;

/** ARINC 429 32 位字的底层位操作。原始表示在导入层保留，这里只做派生规范化。 */
public final class Arinc {
    private Arinc() {}

    /** 解析导入时的原始表示：8 位十六进制（可带 0x）或 32 位二进制串。 */
    public static int parseRaw(String rawText) {
        String t = rawText.trim().toLowerCase();
        if (t.startsWith("0x")) t = t.substring(2);
        if (t.matches("[01]{32}")) return (int) Long.parseLong(t, 2);
        if (t.matches("[0-9a-f]{1,8}")) return (int) Long.parseLong(t, 16);
        throw new IllegalArgumentException("无法解析原始字表示: " + rawText);
    }

    public static int reverse32(int w) {
        int r = 0;
        for (int i = 0; i < 32; i++) r |= ((w >>> i) & 1) << (31 - i);
        return r;
    }

    public static int rev8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) r |= ((b >>> i) & 1) << (7 - i);
        return r;
    }

    /** 奇校验：32 位中 1 的个数应为奇数。 */
    public static boolean parityOk(int w) {
        return (Integer.bitCount(w) & 1) == 1;
    }

    public static int labelByte(int w) { return w & 0xFF; }

    /** label 的八进制展示：只对 label 字节做位序翻转，不改变底层位。 */
    public static String labelOctal(int w) {
        return String.format("%03o", rev8(labelByte(w)));
    }

    public static int sdi(int w) { return (w >>> 8) & 0x3; }

    public static int ssm(int w) { return (w >>> 29) & 0x3; }

    /** 数据域：ARINC 位 11..29，即整数的 bit9..bit27。 */
    public static int data(int w) { return (w >>> 10) & 0x7FFFF; }

    public static String hex8(int w) { return String.format("%08X", w); }

    public static String bits32(int w) { return String.format("%32s", Integer.toBinaryString(w)).replace(' ', '0'); }
}
