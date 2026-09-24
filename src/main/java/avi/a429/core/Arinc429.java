package avi.a429.core;

/**
 * ARINC 429 32-bit word bit-level utilities.
 *
 * Bit numbering follows the spec: bit 1 is the LSB of the 32-bit word as
 * stored in {@code int} (value bit 0), bit 32 is the parity bit (value bit 31).
 * Label occupies bits 1..8 and is transmitted MSB-first, so the octal label
 * is read from the bit-reversed low byte. Displaying the label in octal never
 * changes the underlying bits.
 */
public final class Arinc429 {

    private Arinc429() {}

    /** Reverse all 32 bits (models a source that recorded the word bit-reversed). */
    public static int reverse32(int word) {
        return Integer.reverse(word);
    }

    /** Reverse the 8 bits of a byte (label transmission order). */
    public static int reverse8(int b) {
        b &= 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >>> i) & 1);
        }
        return r;
    }

    /** Label bits 1..8 in transmission order (raw low byte). */
    public static int labelBits(int word) {
        return word & 0xFF;
    }

    /** Octal label as published in equipment docs (bit-reversed low byte), 3 digits. */
    public static String labelOctal(int word) {
        return String.format("%03o", reverse8(word & 0xFF));
    }

    /** SDI: bits 9..10. */
    public static int sdi(int word) {
        return (word >>> 8) & 0x3;
    }

    /** SSM: bits 30..31. */
    public static int ssm(int word) {
        return (word >>> 29) & 0x3;
    }

    /** Data field: bits 11..29 (19 bits). */
    public static int dataField(int word) {
        return (word >>> 10) & 0x7FFFF;
    }

    /** Odd parity over the whole 32-bit word: total count of 1-bits must be odd. */
    public static boolean parityOk(int word) {
        return (Integer.bitCount(word) & 1) == 1;
    }

    public static String toHex(int word) {
        return String.format("%08X", word);
    }

    /** Bits as "1..32" string, MSB (bit 32, parity) first, grouped by 4. */
    public static String bitString(int word) {
        StringBuilder sb = new StringBuilder();
        for (int i = 31; i >= 0; i--) {
            sb.append(((word >>> i) & 1) == 1 ? '1' : '0');
            if (i % 4 == 0 && i != 0) sb.append(' ');
        }
        return sb.toString();
    }
}
