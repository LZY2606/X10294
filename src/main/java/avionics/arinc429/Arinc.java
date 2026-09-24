package avionics.arinc429;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 32位字解析。所有解析都是纯函数，不修改原始位；
 * 八进制 label 只是展示形式，底层位保持不变。
 */
public final class Arinc {

    private Arinc() {}

    /** 解码后的字域视图（原始位仍由调用方保存）。 */
    public record Decoded(
            long raw,
            int labelOctal,        // 八进制 label 数值（如 035）
            String labelOctalText, // 三位八进制文本
            int sdi,               // bits 9-10
            long dataField,        // bits 11-29 (19 bit)
            int ssm,               // bits 30-31
            boolean parityOk,      // bit 32 奇校验
            boolean ssmInvalid     // SSM 处于保留/无效组合
    ) {}

    /** 奇校验：32 位中 1 的总数应为奇数。 */
    public static boolean oddParityOk(long raw) {
        return (Long.bitCount(raw & 0xFFFFFFFFL) & 1) == 1;
    }

    /**
     * label 位于 bit 1-8，线上先传最高位，因此八进制展示需对低字节做位序反转。
     * 位序反转只影响展示/解释，不改动存储的原始位。
     */
    public static int labelOctalOf(long raw) {
        int low = (int) (raw & 0xFFL);
        int rev = Integer.reverse(low) >>> 24;
        return rev; // 0..255，按八进制解读
    }

    public static String octalText(int labelOctal) {
        return String.format("%03o", labelOctal);
    }

    public static Decoded decode(long raw) {
        long r = raw & 0xFFFFFFFFL;
        int label = labelOctalOf(r);
        int sdi = (int) ((r >>> 8) & 0x3L);
        long data = (r >>> 10) & 0x7FFFFL; // 19 bit
        int ssm = (int) ((r >>> 29) & 0x3L);
        boolean parityOk = oddParityOk(r);
        // SSM=3 (binary 11) 在 BNR/BCD 常规字中为保留/无效状态
        boolean ssmInvalid = (ssm == 3);
        return new Decoded(r, label, octalText(label), sdi, data, ssm, parityOk, ssmInvalid);
    }

    /** BNR：19 位数据域二进制补码，bit29 为符号位，乘以字典分辨率。 */
    public static double decodeBnr(long dataField, double resolution) {
        long field = dataField & 0x7FFFFL;
        long signed = (field & 0x40000L) != 0 ? field - 0x80000L : field;
        return signed * resolution;
    }

    public record BcdResult(boolean ok, double value, String diagnostic) {}

    /** BCD：数据域低 16 位为 4 个 BCD 数字；出现 A-F 即非法，保留原值并附诊断。 */
    public static BcdResult decodeBcd(long dataField, double resolution) {
        long field = dataField & 0xFFFFL;
        List<Integer> digits = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            int nib = (int) ((field >>> (i * 4)) & 0xFL);
            if (nib > 9) {
                return new BcdResult(false, Double.NaN,
                        "BCD 非法数字 0x" + Integer.toHexString(nib).toUpperCase()
                                + "（digit " + (4 - i) + "），保留原始数据域 0x"
                                + String.format("%05X", dataField & 0x7FFFFL));
            }
            digits.add(nib);
        }
        int intPart = digits.get(0) * 1000 + digits.get(1) * 100 + digits.get(2) * 10 + digits.get(3);
        return new BcdResult(true, intPart * resolution, null);
    }

    /** discrete：按位解释，返回置位位号列表（数据域内 1..19）。 */
    public static List<Integer> discreteBits(long dataField) {
        List<Integer> bits = new ArrayList<>();
        long field = dataField & 0x7FFFFL;
        for (int i = 0; i < 19; i++) {
            if (((field >>> i) & 1L) == 1L) bits.add(i + 1);
        }
        return bits;
    }
}
