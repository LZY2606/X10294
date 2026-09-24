package gsb;

/** ARINC 429 三十二位字解析。所有位号按 ARINC 约定 1..32。 */
public final class Arinc {
    private Arinc() {}

    public record Fields(int label, int sdi, long data, int ssm, boolean parityBit) {}

    public static Fields fields(long raw32) {
        int label = (int) (raw32 & 0xFF);          // bits 1-8
        int sdi = (int) ((raw32 >> 8) & 0x3);      // bits 9-10
        long data = (raw32 >> 10) & 0x7FFFFL;      // bits 11-29
        int ssm = (int) ((raw32 >> 29) & 0x3);     // bits 30-31
        boolean parity = ((raw32 >> 31) & 1) == 1; // bit 32
        return new Fields(label, sdi, data, ssm, parity);
    }

    /** 奇校验：含校验位在内的 1 的个数必须为奇数。 */
    public static boolean oddParityOk(long raw32) {
        return (Long.bitCount(raw32 & 0xFFFFFFFFL) & 1) == 1;
    }

    /** label 的八进制展示（4 位八进制，如 0203）。展示不改变底层位。 */
    public static String labelOctal(int label) {
        return String.format("%04o", label & 0xFF);
    }

    /** 8 位位序反转，用于位序反转反例候选。 */
    public static int reverseBits8(int v) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((v >> i) & 1);
        }
        return r;
    }

    /** BNR 二进制补码解码；符号位为数据域最高位。 */
    public static double bnr(long data, int bits, double lsb) {
        long mask = (1L << bits) - 1;
        long mag = data & mask;
        if ((mag & (1L << (bits - 1))) != 0) {
            mag -= (1L << bits);
        }
        return mag * lsb;
    }

    /** BCD 解码；任一 nibble 大于 9 时返回 null（非法数字，保留原值）。 */
    public static Long bcd(long data, int digits) {
        long value = 0;
        for (int i = digits - 1; i >= 0; i--) {
            int nibble = (int) ((data >> (i * 4)) & 0xF);
            if (nibble > 9) {
                return null;
            }
            value = value * 10 + nibble;
        }
        return value;
    }
}
