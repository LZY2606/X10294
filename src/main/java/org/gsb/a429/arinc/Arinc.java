package org.gsb.a429.arinc;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 32 位字的纯函数位规则。
 *
 * 位编号遵循 ARINC 429 惯例：bit 1 是传输顺序中的第一位，也即 32 位整数的最低位（LSB0）。
 * 字在传输介质上按 bit 1..bit 32 的顺序发送；label 字段（bit 1..8）按 ARINC 规范做了
 * 位序反转（MSB-first 传输、LSB 编号），因此读取标签号需要先反转这 8 位。
 *
 * 字段布局：
 *   bit 1-8   label（传输位序反转）
 *   bit 9-10  SDI
 *   bit 11-29 data
 *   bit 30-31 SSM
 *   bit 32    parity（奇校验：全 32 位中 1 的个数必须为奇数）
 */
public final class Arinc {

    private Arinc() {
    }

    /** bit n (1..32) 是否为 1。 */
    public static boolean bit(int word, int n) {
        if (n < 1 || n > 32) {
            throw new IllegalArgumentException("bit position out of range 1..32: " + n);
        }
        return ((word >>> (n - 1)) & 1) == 1;
    }

    /** 取 bit lo..hi（闭区间，编号自 1）为无符号整数，lo 位对应结果最低位。 */
    public static int field(int word, int lo, int hi) {
        if (lo < 1 || hi > 32 || lo > hi) {
            throw new IllegalArgumentException("bad field range " + lo + ".." + hi);
        }
        int len = hi - lo + 1;
        if (len == 32) {
            return word;
        }
        int mask = (1 << len) - 1;
        return (word >>> (lo - 1)) & mask;
    }

    /** 反转 32 位字中全部比特的顺序（bit 1 <-> bit 32），返回新的 32 位字。 */
    public static int reverse32(int x) {
        return Integer.reverse(x);
    }

    /** 反转一个 8 位字段内部的比特顺序，结果仍放在低 8 位。 */
    public static int reverse8(int b) {
        int v = b & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>>= 1;
        }
        return r & 0xFF;
    }

    /** 从 32 位原始字取出标签字段（bit 1..8，传输位序，尚未反转）。 */
    public static int labelFieldRaw(int word) {
        return field(word, 1, 8);
    }

    /** 标准读取：反转 label 字段后得到真正的 8 位标签号。 */
    public static int labelCode(int word) {
        return reverse8(labelFieldRaw(word));
    }

    /** 按通道位序读取标签号。若通道传输了整体位反转的字，先整体反转再取标签。 */
    public static int labelCode(int word, boolean wholeWordReversed) {
        return labelCode(wholeWordReversed ? reverse32(word) : word);
    }

    /** 标签号的八进制展示（ARINC 429 标签按八进制书写，如 0o310）。只改展示，不改底层位。 */
    public static String labelOctal(int labelCode) {
        return String.format("%03o", labelCode & 0xFF);
    }

    public static int sdi(int word) {
        return field(word, 9, 10);
    }

    public static int sdi(int word, boolean wholeWordReversed) {
        return sdi(wholeWordReversed ? reverse32(word) : word);
    }

    public static int ssm(int word) {
        return field(word, 30, 31);
    }

    public static int ssm(int word, boolean wholeWordReversed) {
        return ssm(wholeWordReversed ? reverse32(word) : word);
    }

    public static int parityBit(int word) {
        return field(word, 32, 32);
    }

    public static int parityBit(int word, boolean wholeWordReversed) {
        return parityBit(wholeWordReversed ? reverse32(word) : word);
    }

    /** 数据域 bit 11..29 的 19 位无符号原值。 */
    public static int dataField(int word) {
        return field(word, 11, 29);
    }

    public static int dataField(int word, boolean wholeWordReversed) {
        return dataField(wholeWordReversed ? reverse32(word) : word);
    }

    /** 奇校验：32 位整体 1 的个数为奇数。 */
    public static boolean parityValid(int word) {
        return Integer.bitCount(word & 0xFFFFFFFFL) % 2 == 1;
    }

    public static boolean parityValid(int word, boolean wholeWordReversed) {
        return parityValid(wholeWordReversed ? reverse32(word) : word);
    }

    /** 令 bit 32 取满足奇校验所需的值（仅翻转校验位），返回合法奇校验字。 */
    public static int setOddParity(int wordNoParity) {
        int w = wordNoParity & 0x7FFFFFFF;
        if (Integer.bitCount(w) % 2 == 0) {
            w |= 0x80000000;
        }
        return w;
    }

    /** SSM 分类（BNR 与 BCD/discrete 的语义由字典/编码类型决定，这里给出统一名称与原始码）。 */
    public enum SsmState {
        NORMAL_OPERATION(0b00),
        FUNCTIONAL_TEST(0b01),
        NO_COMPUTED_DATA(0b10),
        NO_WARNING_FAULT(0b11);

        public final int code;

        SsmState(int code) {
            this.code = code;
        }

        public static SsmState of(int code) {
            for (SsmState s : values()) {
                if (s.code == (code & 0b11)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("bad ssm code " + code);
        }
    }

    /**
     * SSM 是否表示“功能有效数据”。仅 NORMAL_OPERATION 可进入聚合值；
     * NO_COMPUTED_DATA 为明确无效状态（固定案例中的 SSM 无效情形）。
     */
    public static boolean ssmFunctional(int ssmCode) {
        return SsmState.of(ssmCode) == SsmState.NORMAL_OPERATION;
    }

    /** 32 位字拆成稳定的位域段，供 SVG/页面展示。 */
    public static List<BitBand> bands() {
        List<BitBand> out = new ArrayList<>();
        out.add(new BitBand("parity", 32, 32));
        out.add(new BitBand("ssm", 30, 31));
        out.add(new BitBand("data", 11, 29));
        out.add(new BitBand("sdi", 9, 10));
        out.add(new BitBand("label", 1, 8));
        return out;
    }

    /** 以 bit 1 为最低位顺序输出 32 个 0/1 字符（索引 0 对应 bit 1）。 */
    public static String bitsLsbFirst(int word) {
        StringBuilder sb = new StringBuilder(32);
        for (int n = 1; n <= 32; n++) {
            sb.append(bit(word, n) ? '1' : '0');
        }
        return sb.toString();
    }

    public record BitBand(String name, int lo, int hi) {
        public int width() {
            return hi - lo + 1;
        }
    }
}
