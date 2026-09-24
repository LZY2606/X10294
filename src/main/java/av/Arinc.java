package av;

/**
 * ARINC 429 32-bit word bit-level decoding.
 *
 * Bit layout (ARINC bit numbers are 1-based; here bit index 0 == ARINC bit 1):
 *   bits 0..7   label (transmitted MSB-first, octal display reverses the byte)
 *   bits 8..9   SDI
 *   bits 10..28 data field (19 bits)
 *   bits 29..30 SSM
 *   bit  31     parity (odd)
 *
 * Displaying a label in octal never changes the underlying bits; it is a pure
 * view transform (rule: octal-label-display-preserves-bits).
 */
public final class Arinc {

    private Arinc() {}

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

    public static int labelByte(long w) {
        return (int) (w & 0xFF);
    }

    /** Octal label as displayed on the bus, e.g. "203". Pure view transform. */
    public static String labelOctal(long w) {
        return String.format("%03o", reverse8(labelByte(w)));
    }

    public static int sdi(long w) {
        return (int) ((w >> 8) & 3);
    }

    public static int ssm(long w) {
        return (int) ((w >> 29) & 3);
    }

    public static long dataField(long w) {
        return (w >> 10) & 0x7FFFFL;
    }

    public static int parityBit(long w) {
        return (int) ((w >> 31) & 1);
    }

    /** ARINC 429 uses odd parity across all 32 bits. */
    public static boolean parityOk(long w) {
        return Long.bitCount(w & 0xFFFFFFFFL) % 2 == 1;
    }

    /**
     * BNR two's-complement decode over the low {@code sigBits} of the data field.
     * Engineering value = raw * scale + offset, unit supplied by the dictionary.
     */
    public static double decodeBnr(long dataField, int sigBits, double scale, double offset) {
        long mask = (1L << sigBits) - 1;
        long raw = dataField & mask;
        long signBit = 1L << (sigBits - 1);
        long signed = (raw & signBit) != 0 ? raw - (1L << sigBits) : raw;
        return signed * scale + offset;
    }

    /**
     * BCD decode of 4 nibbles (bits 18..3 of the data field, MSD first).
     * Returns null when any nibble is an illegal BCD digit (A..F); the caller
     * must keep the raw value in place and attach a diagnostic instead of
     * dropping the word.
     */
    public static Long decodeBcd(long dataField) {
        long value = 0;
        for (int n = 3; n >= 0; n--) {
            long nibble = (dataField >> (3 + n * 4)) & 0xF;
            if (nibble > 9) return null;
            value = value * 10 + nibble;
        }
        return value;
    }

    /** SSM semantics for BNR words per the dictionary-driven table. */
    public static String ssmMeaning(int ssm) {
        return switch (ssm) {
            case 3 -> "normal-operation";
            case 0 -> "failure-warning";
            case 1 -> "no-computed-data";
            case 2 -> "functional-test";
            default -> "unknown";
        };
    }

    /** An SSM state is invalid for value use when it is not normal-operation. */
    public static boolean ssmValidForValue(int ssm) {
        return ssm == 3;
    }
}
