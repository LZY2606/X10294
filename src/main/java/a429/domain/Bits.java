package a429.domain;

/**
 * ARINC 429 位级原语。
 *
 * 编号约定（与 ARINC 429 串行传输一致）：
 *   bit 1 .. bit 8   = label（octal，bit 1 先发）
 *   bit 9..10        = SDI
 *   bit 11..29       = data（bit 29 = MSB）
 *   bit 30..31       = SSM
 *   bit 32           = parity（奇校验）
 *
 * 原始字始终保留：{@code raw} 为采集端给出的 32 位无符号值，
 * {@code wireReversed} 表示采集端是否已按“先发位为整数 bit0”摆放
 * （即与上述编号相反）。显示层的八进制 label 只是表示形式，不改动 raw。
 */
public final class Bits {

    public static final int WORD_BITS = 32;
    public static final long FULL_MASK = 0xFFFF_FFFFL;

    private Bits() {
    }

    /** 读取 ARINC 线编号 bit（1..32）：bit 1 最先发送（label LSB），bit 32 为奇偶位。 */
    public static int wireBit(long raw, boolean wireReversed, int bitNumber) {
        if (bitNumber < 1 || bitNumber > WORD_BITS) {
            throw new IllegalArgumentException("ARINC bit number must be 1..32, got " + bitNumber);
        }
        int integerIndex = wireReversed ? (WORD_BITS - bitNumber) : (bitNumber - 1);
        return (int) ((raw >>> integerIndex) & 1L);
    }

    /** 读取 ARINC 线编号区间 [hi..lo] 的无符号值，hi 为编号更大（更靠近奇偶位）的位。 */
    public static long wireField(long raw, boolean wireReversed, int hi, int lo) {
        if (hi < lo || lo < 1 || hi > WORD_BITS) {
            throw new IllegalArgumentException("bad field [" + lo + ".." + hi + "]");
        }
        long value = 0;
        for (int b = lo; b <= hi; b++) {
            value = (value << 1) | wireBit(raw, wireReversed, b);
        }
        return value;
    }

    /** 32 位整体位序反转（采集通道方向错误的反例来源）。 */
    public static long reverseWord(long raw) {
        long v = raw & FULL_MASK;
        long r = 0;
        for (int i = 0; i < WORD_BITS; i++) {
            r = (r << 1) | (v & 1L);
            v >>>= 1;
        }
        return r;
    }

    /** label 字段（wire bit 1..8）的整数值，范围 0..255。 */
    public static int labelValue(long raw, boolean wireReversed) {
        return (int) wireField(raw, wireReversed, 8, 1);
    }

    /** label 的八进制展示，如 305；仅为表示，不改变任何位。 */
    public static String labelOctal(long raw, boolean wireReversed) {
        return String.format("%03o", labelValue(raw, wireReversed));
    }

    public static int sdi(long raw, boolean wireReversed) {
        return (int) wireField(raw, wireReversed, 10, 9);
    }

    public static int ssm(long raw, boolean wireReversed) {
        return (int) wireField(raw, wireReversed, 31, 30);
    }

    /** 奇校验：全部 32 位 1 的个数应为奇数。 */
    public static boolean oddParityOk(long raw, boolean wireReversed) {
        int ones = 0;
        for (int b = 1; b <= WORD_BITS; b++) {
            ones += wireBit(raw, wireReversed, b);
        }
        return (ones & 1) == 1;
    }

    public static int parityBit(long raw, boolean wireReversed) {
        return wireBit(raw, wireReversed, 32);
    }

    /** 翻转 parity 位（wire bit 32），返回新原始值。 */
    public static long flipParity(long raw, boolean wireReversed) {
        int integerIndex = wireReversed ? 0 : 31;
        return (raw ^ (1L << integerIndex)) & FULL_MASK;
    }

    /** 归一化为线编号摆放（bit32 在整数最高位），便于统一解析。 */
    public static long normalizeToWire(long raw, boolean wireReversed) {
        return wireReversed ? reverseWord(raw) : (raw & FULL_MASK);
    }

    /** 生成一个 32 位字，按线编号给各字段赋值并自动补奇校验位（bit32）。 */
    public static long compose(int label, int sdi, long data19, int ssm) {
        long word = (label & 0xFFL)
                | ((long) (sdi & 0x3) << 8)
                | ((data19 & 0x7FFFFL) << 10)
                | ((long) (ssm & 0x3) << 29);
        int ones = Long.bitCount(word);
        if ((ones & 1) == 0) {
            word |= 1L << 31; // wire bit 32 -> integer index 31
        }
        return word & FULL_MASK;
    }
}
