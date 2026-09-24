package av429.codecs;

/**
 * ARINC 429 32 位字的线位工具。
 *
 * 位编号采用规范：bit 1 是线上先发的 LSB，bit 32 是奇偶位（MSB）。
 * 线位布局：label=1..8, SDI=9..10, data=11..29, SSM=30..31, parity=32。
 * label 在线上按 LSB-first 传输，因此字典里的八进制 label 是 8 位反转后的值；
 * 反转仅用于展示，不改变底层位（{@link #labelCode(int)} 永远返回线位原值）。
 */
public final class Arinc429 {
    public static final int LABEL_BITS = 8;
    public static final int DATA_BITS = 19;

    private Arinc429() {}

    /** 取第 n 位（n: 1..32，1=LSB）。 */
    public static int bit(int word, int n) {
        return (word >>> (n - 1)) & 1;
    }

    /** 取连续位域 [lo..hi]，返回值右对齐到 LSB。 */
    public static int field(int word, int hi, int lo) {
        int width = hi - lo + 1;
        int mask = width == 32 ? -1 : ((1 << width) - 1);
        return (word >>> (lo - 1)) & mask;
    }

    public static int labelCode(int word) { return word & 0xFF; }
    public static int sdi(int word) { return field(word, 10, 9); }
    public static int data(int word) { return field(word, 29, 11); }
    public static int ssm(int word) { return field(word, 31, 30); }
    public static int parityBit(int word) { return bit(word, 32); }

    /** ARINC 429 使用奇校验：32 位中 1 的个数为奇数即通过。 */
    public static boolean oddParityValid(int word) {
        return (Integer.bitCount(word) & 1) == 1;
    }

    /** 把第 32 位置成使整字满足奇校验的值，返回新字（不改其它位）。 */
    public static int withOddParity(int wordWithoutParity) {
        int w = wordWithoutParity & 0x7FFFFFFF;
        if ((Integer.bitCount(w) & 1) == 0) {
            w |= 0x80000000;
        }
        return w;
    }

    /** 翻转奇偶位（制造奇偶错误），其余位不变。 */
    public static int flipParity(int word) { return word ^ 0x80000000; }

    /** 8 位反转：label 线位码 &lt;-&gt; 人工八进制 label。 */
    public static int reverse8(int b) { return Integer.reverse(b & 0xFF) >>> 24; }

    /** 32 位整体反转，模拟“位序反转”（MSB-first 误采集）。 */
    public static int reverse32(int word) { return Integer.reverse(word); }

    /**
     * label 的八进制展示。displayReversed=true（规范默认）时先把 8 位反转再按三位八进制展示；
     * false 时直接展示线位码。两种模式底层 {@link #labelCode(int)} 完全不变。
     */
    public static String octalLabel(int word, boolean displayReversed) {
        int code = labelCode(word);
        int shown = displayReversed ? reverse8(code) : code;
        return String.format("%03o", shown);
    }

    /** 人工八进制 label（如 310）转线上 label 码（LSB-first）。 */
    public static int wireLabelFromOctal(int octal) { return reverse8(octal & 0xFF); }

    /** 组装一个字：label 为线位码，自动补奇校验。 */
    public static int build(int labelCode, int sdi, int data19, int ssm2) {
        int w = (labelCode & 0xFF)
                | ((sdi & 0x3) << 8)
                | ((data19 & 0x7FFFF) << 10)
                | ((ssm2 & 0x3) << 29);
        return withOddParity(w);
    }

    public static String hex(int word) { return String.format("%08X", word); }

    public static int parseHex(String hex) {
        long v = Long.parseLong(hex.trim(), 16);
        if (v > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("超出 32 位原始字范围: " + hex);
        }
        return (int) v;
    }

    // ---------- BNR（二进制补码工程值） ----------

    /** 19 位数据域按补码解释；resolution 为 bit 11（LSB）对应的工程量。 */
    public static long bnrSigned(int data19, boolean signed) {
        long raw = data19 & 0x7FFFFL;
        if (signed && (raw & 0x40000L) != 0) {
            raw -= 1L << 19;
        }
        return raw;
    }

    /** 工程值编码为 19 位补码数据域（按 resolution 取整）。 */
    public static int encodeBnr(double engineering, double resolution, boolean signed) {
        long counts = Math.round(engineering / resolution);
        long max = signed ? (1L << 18) - 1 : (1L << 19) - 1;
        long min = signed ? -(1L << 18) : 0;
        if (counts > max || counts < min) {
            throw new IllegalArgumentException("BNR 工程值超出 19 位范围: " + engineering);
        }
        return (int) (counts & 0x7FFFFL);
    }

    // ---------- BCD（二-十进制） ----------

    /**
     * BCD 数字最多 4 个，最高位在 bits 29..26，依次向低位排列。
     * 返回的每个数为 0..15；调用方需判定 10..15 为非法数字。
     */
    public static int[] bcdNibbles(int word, int digitCount) {
        if (digitCount < 1 || digitCount > 4) {
            throw new IllegalArgumentException("BCD 位数需在 1..4 之间");
        }
        int[] digits = new int[digitCount];
        for (int i = 0; i < digitCount; i++) {
            int hi = 29 - 4 * i;
            digits[i] = field(word, hi, hi - 3);
        }
        return digits;
    }

    public static int encodeBcd(int[] digits) {
        int data = 0;
        for (int i = 0; i < digits.length; i++) {
            if (digits[i] < 0 || digits[i] > 9) {
                throw new IllegalArgumentException("BCD 编码只接受 0..9: " + digits[i]);
            }
            data |= digits[i] << (15 - 4 * i);
        }
        return data;
    }
}
