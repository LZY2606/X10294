package a429.domain;

/**
 * ARINC 429 bit-field helpers.
 *
 * A received 32-bit word is held in an unsigned 32-bit {@code long}.
 * ARINC bit numbering runs 1..32 from the label end to the parity end;
 * when the word is an integer, ARINC bit <em>n</em> is integer bit {@code 32-n}.
 */
public final class Bits {
    private Bits() {
    }

    /** Inclusive field addressed by ARINC bit numbers (msb >= lsb). */
    public static int field(long word, int msbArinc, int lsbArinc) {
        if (msbArinc < lsbArinc || msbArinc > 32 || lsbArinc < 1) {
            throw new IllegalArgumentException("bad field " + msbArinc + ".." + lsbArinc);
        }
        int width = msbArinc - lsbArinc + 1;
        int lsbIndex = 32 - lsbArinc;
        return (int) ((word >>> lsbIndex) & ((1L << width) - 1));
    }

    /** ARINC bits 1..8 as transmitted on the wire (LSB-first label byte). */
    public static int labelByte(long word) {
        return (int) ((word >>> 24) & 0xFF);
    }

    /** ARINC bits 9..10. */
    public static int sdi(long word) {
        return field(word, 10, 9);
    }

    /** ARINC bits 11..29 (the 19-bit data field). */
    public static int dataField(long word) {
        return field(word, 29, 11);
    }

    /** ARINC bits 30..31. */
    public static int ssm(long word) {
        return field(word, 31, 30);
    }

    /** ARINC bit 32. */
    public static int parityBit(long word) {
        return (int) (word & 1);
    }

    /** Reverse the eight bits of a label byte (wire order &lt;-&gt; canonical order). */
    public static int reverse8(int b) {
        return Integer.reverse(b & 0xFF) >>> 24;
    }

    /** Reverse the order of all 32 bits (the 位序反転 boundary case). */
    public static long reverse32(long word) {
        return Integer.reverse((int) word) & 0xFFFFFFFFL;
    }

    /** ARINC 429 uses odd parity across all 32 bits. */
    public static boolean oddParityOk(long word) {
        return Long.bitCount(word & 0xFFFFFFFFL) % 2 == 1;
    }

    /** Parity bit that makes the low 31 bits form an odd-parity 32-bit word. */
    public static int requiredParityBit(long wordWithoutParity) {
        int ones = Long.bitCount((wordWithoutParity >>> 1) & 0xFFFFFFFFL);
        return (ones & 1) == 0 ? 1 : 0;
    }

    /** Sign-extend a {@code width}-bit two's-complement field. */
    public static long signExtend(long value, int width) {
        long mask = 1L << (width - 1);
        return (value ^ mask) - mask;
    }

    public static String octal3(int canonicalLabel) {
        return String.format("%03o", canonicalLabel & 0xFF);
    }
}
