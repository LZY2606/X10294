package org.avionics.review.domain;

/**
 * Bit helpers for 32-bit ARINC 429 words.
 *
 * <p>The wire order of an ARINC 429 word is bit 1 (LSB) first ... bit 32 (parity) last.
 * Internally a raw word is kept as an unsigned 32-bit value (Java int) with bit 32 in the
 * sign bit, i.e. {@code raw &amp; 0x80000000} is the parity bit.  All field ranges in this
 * code base use 1-based ARINC bit numbers (inclusive on both ends).
 */
public final class Bits {
    private Bits() {
    }

    /** Extract bits {@code from..to} (1-based wire numbers, {@code from} is the LSB side). */
    public static int field(int raw, int from, int to) {
        if (from < 1 || to > 32 || from > to) {
            throw new IllegalArgumentException("bad bit range " + from + ".." + to);
        }
        int width = to - from + 1;
        int mask = width == 32 ? -1 : ((1 << width) - 1);
        return (raw >>> (from - 1)) & mask;
    }

    /** Reverse all 32 bits: returns the value as if the wire had been sampled MSB-first. */
    public static int reverse32(int x) {
        return Integer.reverse(x);
    }

    /**
     * ARINC 429 label: bits 1..8 as transmitted, displayed MSB-first as three octal digits.
     * The 8-bit label field is the bit-reverse of the wire LSB-first label byte.
     */
    public static int labelValue(int raw) {
        return Integer.reverseBytes(field(raw, 1, 8) << 24) >>> 24;
    }

    /** Three-digit octal label string, e.g. 205 -> "315". */
    public static String labelOctal(int labelValue) {
        return String.format("%03o", labelValue & 0xFF);
    }

    /** Odd parity check: the total count of set bits 1..32 must be odd. */
    public static boolean oddParityOk(int raw) {
        return Integer.bitCount(raw) % 2 == 1;
    }

    public static int setOddParity(int withoutParity) {
        int v = withoutParity & 0x7FFFFFFF;
        if (Integer.bitCount(v) % 2 == 0) {
            v |= 0x80000000;
        }
        return v;
    }

    /** Render the 32 bits as a wire-order string, bit 32 first ... bit 1 last. */
    public static String toWireString(int raw) {
        StringBuilder sb = new StringBuilder(35);
        for (int bit = 32; bit >= 1; bit--) {
            sb.append((raw >>> (bit - 1)) & 1);
            if (bit == 32 || bit == 29 || bit == 11 || bit == 9) {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
