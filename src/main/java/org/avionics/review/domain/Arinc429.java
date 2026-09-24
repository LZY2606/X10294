package org.avionics.review.domain;

/**
 * Pure ARINC 429 word mathematics. No I/O, no persistence - so that every
 * decoding rule has a deterministic unit test.
 *
 * <p>A word is stored as a Java {@code long} holding the unsigned 32-bit
 * value. ARINC bit numbering puts label bit 1 at the LSB (label is transmitted
 * LSB first over the wire) and parity at bit 32.</p>
 */
public final class Arinc429 {

    public static final int LABEL_BITS = 8;
    public static final int SDI_BITS = 2;
    public static final int SSM_BITS = 2;
    public static final int PARITY_BIT = 32;

    private Arinc429() {
    }

    public static boolean bitAt(long word, int arincBit) {
        return ((word >>> (arincBit - 1)) & 1L) == 1L;
    }

    /** Inclusive ARINC bit field, returned so that {@code msb} is the high bit. */
    public static int field(long word, int msb, int lsb) {
        int width = msb - lsb + 1;
        long mask = width == 32 ? 0xFFFFFFFFL : ((1L << width) - 1L);
        return (int) ((word >>> (lsb - 1)) & mask);
    }

    public static int labelRaw(long word) {
        return field(word, 8, 1);
    }

    public static int sdi(long word) {
        return field(word, 10, 9);
    }

    public static int ssm(long word) {
        return field(word, 30, 29);
    }

    /**
     * Conventional ARINC octal label: the 8 on-wire bits (LSB-first) are
     * reversed into the human octal number. This is presentation only - it
     * never rewrites the stored word.
     */
    public static String labelOctal(long word) {
        return String.format("%03o", reverse8(labelRaw(word)));
    }

    public static int reverse8(int value) {
        int v = value & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>>= 1;
        }
        return r & 0xFF;
    }

    /** Reverse every one of the 32 ARINC bit positions (a feed inversion). */
    public static long reverse32(long word) {
        long w = word & 0xFFFFFFFFL;
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | (w & 1L);
            w >>>= 1;
        }
        return r;
    }

    /** ARINC 429 uses odd parity over all 32 bits including the parity bit. */
    public static boolean oddParityOk(long word) {
        return (Long.bitCount(word & 0xFFFFFFFFL) & 1) == 1;
    }

    public static long forceParity(long word) {
        long w = word & 0xFFFFFFFFFFFFFFFEL;
        if (Long.bitCount(w) % 2 == 0) {
            w |= 1L;
        }
        return w;
    }

    public static long breakParity(long word) {
        return word ^ (1L << (PARITY_BIT - 1));
    }

    public static boolean ssmIsValid(int ssm, String encoding) {
        if ("BNR".equalsIgnoreCase(encoding) || "BCD".equalsIgnoreCase(encoding)) {
            return ssm == 0b00;
        }
        return true;
    }

    public static String ssmName(int ssm, String encoding) {
        if ("DISCRETE".equalsIgnoreCase(encoding)) {
            return switch (ssm) {
                case 0b00 -> "NORMAL_OPERATION";
                case 0b01 -> "FUNCTIONAL_TEST";
                case 0b10 -> "NO_COMPUTED_DATA";
                default -> "FUNCTIONAL_FAILURE";
            };
        }
        return switch (ssm) {
            case 0b00 -> "NORMAL_OPERATION";
            case 0b01 -> "FUNCTIONAL_TEST";
            case 0b10 -> "NOT_COMPUTED_DATA";
            default -> "FAILURE_WARNING";
        };
    }

    /**
     * Signed BNR conversion. Symmetric signed range (e.g. +/-180 deg for
     * pitch attitude); the MSB is the sign bit and resolution is
     * {@code range / 2^(n-1)}.
     */
    public static double bnrSigned(long word, int msb, int lsb, double range) {
        int width = msb - lsb + 1;
        int raw = field(word, msb, lsb);
        if ((raw & (1 << (width - 1))) != 0) {
            raw -= (1 << width);
        }
        double resolution = range / (1L << (width - 1));
        return raw * resolution;
    }

    public static double bnrUnsigned(long word, int msb, int lsb, double range) {
        int width = msb - lsb + 1;
        long raw = field(word, msb, lsb) & 0xFFFFFFFFL;
        double resolution = range / ((1L << width) - 1);
        return raw * resolution;
    }

    public static int setBnrSigned(long[] holder, int msb, int lsb, double range, double value) {
        int width = msb - lsb + 1;
        double resolution = range / (1L << (width - 1));
        long quant = Math.round(value / resolution);
        long mask = (1L << width) - 1L;
        int raw = (int) (quant & mask);
        holder[0] = (holder[0] & ~(mask << (lsb - 1))) | ((raw & mask) << (lsb - 1));
        return raw;
    }

    /**
     * Read one BCD digit. Returns {@code 0x10} when the nibble is not a legal
     * decimal digit so the caller can keep the word and attach a diagnostic
     * instead of dropping it.
     */
    public static int bcdDigit(long word, int nibbleMsb, int nibbleLsb) {
        int nibble = field(word, nibbleMsb, nibbleLsb);
        if ((nibble & 0xF) > 9) {
            return 0x10;
        }
        return nibble;
    }

    public static void setBcdDigit(long[] holder, int nibbleMsb, int nibbleLsb, int digit) {
        long mask = 0xFL << (nibbleLsb - 1);
        holder[0] = (holder[0] & ~mask) | ((long) (digit & 0xF) << (nibbleLsb - 1));
    }

    public static String toHex32(long word) {
        return String.format("%08X", word & 0xFFFFFFFFL);
    }

    public static long fromHex(String hex) {
        return Long.parseLong(hex.trim(), 16) & 0xFFFFFFFFL;
    }

    public static long setField(long word, int msb, int lsb, int value) {
        long mask = (1L << (msb - lsb + 1)) - 1L;
        return (word & ~(mask << (lsb - 1))) | ((value & mask) << (lsb - 1));
    }
}
