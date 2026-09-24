package avio.a429.core;

/**
 * ARINC 429 32-bit word primitives.
 *
 * Bit numbering follows the spec: bit 1 is transmitted first and is the LSB of
 * the stored 32-bit value. The label occupies bits 1-8; because octal label
 * digits are defined MSB-first in transmission order, the octal label is the
 * bit-reversal of the low byte. Displaying a label in octal never changes the
 * underlying bits.
 */
public final class Arinc {
    private Arinc() {}

    public static final int SSM_FAILURE = 0;   // bits 30-31 = 00
    public static final int SSM_NCD = 1;       // 01 No Computed Data
    public static final int SSM_TEST = 2;      // 10 Functional Test
    public static final int SSM_NORMAL = 3;    // 11 Normal Operation

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >> i) & 1);
        }
        return r;
    }

    /** Octal label value (0..0377) decoded from bits 1-8 with the spec bit reversal. */
    public static int labelOctal(long word) {
        return reverse8((int) (word & 0xFF));
    }

    /** Label byte as it sits in bits 1-8, without reversal. */
    public static int labelByteRaw(long word) {
        return (int) (word & 0xFF);
    }

    public static String labelOctalText(long word) {
        return String.format("%03o", labelOctal(word));
    }

    public static int sdi(long word) {
        return (int) ((word >> 8) & 0x3);
    }

    /** SSM bits 30-31 (equipment status). */
    public static int ssm(long word) {
        return (int) ((word >> 29) & 0x3);
    }

    /** BNR sign bit (bit 29). */
    public static boolean bnrSign(long word) {
        return ((word >> 28) & 1) == 1;
    }

    /** Raw 18-bit BNR magnitude field, bits 11-28. */
    public static long bnrMagnitude(long word) {
        return (word >> 10) & 0x3FFFF;
    }

    /** Signed BNR value: two's complement over the 18-bit data field when sign bit set. */
    public static long bnrSigned(long word) {
        long mag = bnrMagnitude(word);
        if (bnrSign(word)) {
            return mag - (1L << 18);
        }
        return mag;
    }

    /** BCD nibbles of the data field, bits 11-26 (4 nibbles, digit0 = least significant). */
    public static int[] bcdDigits(long word) {
        int[] digits = new int[4];
        for (int i = 0; i < 4; i++) {
            digits[i] = (int) ((word >> (10 + i * 4)) & 0xF);
        }
        return digits;
    }

    /** Odd parity over all 32 bits: total number of 1-bits must be odd. */
    public static boolean parityOk(long word) {
        return Long.bitCount(word & 0xFFFFFFFFL) % 2 == 1;
    }

    public static String ssmText(int ssm) {
        return switch (ssm) {
            case SSM_FAILURE -> "FAILURE_WARNING";
            case SSM_NCD -> "NO_COMPUTED_DATA";
            case SSM_TEST -> "FUNCTIONAL_TEST";
            default -> "NORMAL";
        };
    }

    /** A word participates in aggregation only when parity is odd and SSM is NORMAL. */
    public static boolean aggregatable(long word) {
        return parityOk(word) && ssm(word) == SSM_NORMAL;
    }

    /** Encode a word (test/case-pack helper). */
    public static long encode(int labelOct, int sdi, long data19, int ssm, boolean badParity) {
        long w = reverse8(labelOct & 0xFF);
        w |= (long) (sdi & 3) << 8;
        w |= (data19 & 0x7FFFFL) << 10;
        w |= (long) (ssm & 3) << 29;
        boolean needParityBit = Long.bitCount(w) % 2 == 0;
        if (needParityBit != badParity) {
            w |= 1L << 31;
        }
        return w;
    }
}
