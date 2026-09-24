package com.avionics.a429.domain;

/**
 * Bit conventions for a received ARINC 429 word.
 *
 * The 32-bit raw word is stored in an {@code int} where bit 0 of the int is
 * ARINC bit 1 (transmitted first / LSB on the wire in the chosen ingest
 * convention) and bit 31 is ARINC bit 32 (parity). All display and decoding
 * operations read from this representation; the stored raw bits are never
 * rewritten by interpretation layers.
 */
public final class Bits {

    private Bits() {
    }

    public static int bit(int raw, int arincPositionOneBased) {
        return (raw >>> (arincPositionOneBased - 1)) & 1;
    }

    public static int field(int raw, int msbArinc, int lsbArinc) {
        int width = msbArinc - lsbArinc + 1;
        int value = 0;
        for (int p = msbArinc; p >= lsbArinc; p--) {
            value = (value << 1) | bit(raw, p);
        }
        return value;
    }

    public static int labelBits(int raw) {
        return field(raw, 8, 1);
    }

    /** Octal label read with bit 1 as most significant octet-bit (raw order). */
    public static String octalRaw(int raw) {
        return String.format("%03o", labelBits(raw));
    }

    /**
     * Standard ARINC label octal: the label field is transmitted MSB first, so
     * the eight label bits are reversed before grouping into three octal
     * digits. This is display only; the underlying bits are untouched.
     */
    public static String octalStandard(int raw) {
        int reversed = Integer.reverse(labelBits(raw)) >>> 24;
        return String.format("%03o", reversed);
    }

    public static int reverseAll32(int raw) {
        return Integer.reverse(raw);
    }

    public static int popCount(int raw) {
        return Integer.bitCount(raw);
    }

    /** ARINC 429 uses odd parity over all 32 bits. */
    public static boolean oddParityOk(int raw) {
        return (popCount(raw) & 1) == 1;
    }

    /** Flip parity bit (32) so the word fails the odd-parity check. */
    public static int corruptParity(int raw) {
        return raw ^ 0x8000_0000;
    }

    public static String toBinary(int raw) {
        StringBuilder sb = new StringBuilder(32);
        for (int p = 32; p >= 1; p--) {
            sb.append(bit(raw, p));
            if (p == 32 || p == 30 || p == 29 || p == 9) {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
