package avionics.core;

/**
 * ARINC 429 32位原始字解析。
 * 位编号 1..32（1 = 总线上先到）。label 在 bit1..8，先传最高位，
 * 因此八进制 label 展示 = 低8位按位反转；展示形式不改变底层位。
 */
public final class Arinc429 {

    public record Parsed(long raw, String rawHex, int labelByte, String labelOctal,
                         int sdi, long dataField, int ssm, int parityBit, boolean parityOk) {}

    private Arinc429() {}

    public static Parsed parse(long raw) {
        long w = raw & 0xFFFFFFFFL;
        int low8 = (int) (w & 0xFF);
        int labelByte = Integer.reverse(low8) >>> 24;
        int sdi = (int) ((w >>> 8) & 0x3);
        long data = (w >>> 10) & 0x7FFFFL; // bit11..29, 19 bits
        int ssm = (int) ((w >>> 29) & 0x3);
        int parityBit = (int) ((w >>> 31) & 0x1);
        boolean parityOk = (Long.bitCount(w) & 1) == 1; // 奇校验：32位中1的个数为奇
        String oct = Integer.toOctalString(labelByte);
        while (oct.length() < 3) oct = "0" + oct;
        return new Parsed(w, String.format("%08X", w), labelByte, oct, sdi, data, ssm, parityBit, parityOk);
    }

    /** 位序反转换句：把 bit1..8 当作“先传最低位”解释时得到的候选 label。 */
    public static int reversedLabelByte(int labelByte) {
        return Integer.reverse(labelByte & 0xFF) >>> 24;
    }

    /** 组装一个原始字（测试与案例包生成用）。 */
    public static long compose(int labelByte, int sdi, long data, int ssm) {
        int revLabel = Integer.reverse(labelByte & 0xFF) >>> 24;
        long w = (revLabel & 0xFFL)
                | ((sdi & 0x3L) << 8)
                | ((data & 0x7FFFFL) << 10)
                | ((ssm & 0x3L) << 29);
        int ones = Long.bitCount(w);
        if ((ones & 1) == 0) w |= (1L << 31); // 置奇偶位使总1数为奇
        return w & 0xFFFFFFFFL;
    }

    /** 强制制造一个奇偶错误字（翻转奇偶位）。 */
    public static long withBadParity(long raw) {
        return (raw ^ (1L << 31)) & 0xFFFFFFFFL;
    }
}
