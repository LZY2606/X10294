package av.review;

/**
 * ARINC 429 32-bit word primitives.
 *
 * Bit numbering follows the wire convention: bit 1 is the LSB (transmitted
 * first) and bit 32 is the MSB (odd parity). The label field (bits 1..8) is
 * transmitted least-significant-bit first, so the human-facing octal label is
 * obtained by reversing those eight bits. Reversing bits is a presentation
 * transform only: it never mutates the stored word.
 */
public final class Arinc {

    private Arinc() {
    }

    public static final int LABEL_BITS = 8;
    public static final int DATA_BITS = 19;

    /** Reverse the 8 low-order bits of the given value (label field). */
    public static int reverseBits8(int value) {
        int v = value & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>= 1;
        }
        return r & 0xFF;
    }

    /** Reverse all 32 bits (counterexample: full bit-order reversal). */
    public static long reverseBits32(long word) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | (word & 1L);
            word >>= 1;
        }
        return r & 0xFFFFFFFFL;
    }

    /** Label as an integer in the 0..255 range (octal presentation lives elsewhere). */
    public static int labelValue(long word) {
        return reverseBits8((int) (word & 0xFFL));
    }

    /** Human-facing three digit octal label, e.g. {@code 203}. Presentation only. */
    public static String labelOctal(long word) {
        return String.format("%03o", labelValue(word));
    }

    /** Raw label bits exactly as they sit in the word (bits 1..8). */
    public static int labelBitsRaw(long word) {
        return (int) (word & 0xFFL);
    }

    public static int sdi(long word) {
        return (int) ((word >>> 8) & 0x3L);
    }

    public static long dataField(long word) {
        return (word >>> 10) & 0x7FFFFL;
    }

    public static int ssm(long word) {
        return (int) ((word >>> 29) & 0x3L);
    }

    public static int parityBit(long word) {
        return (int) ((word >>> 31) & 0x1L);
    }

    /** ARINC 429 uses odd parity over all 32 bits. */
    public static boolean oddParityOk(long word) {
        return Integer.bitCount((int) (word & 0xFFFFFFFFL)) % 2 == 1;
    }

    /** Force bit 32 so the 32-bit word carries odd parity; other bits are untouched. */
    public static long applyOddParity(long word) {
        long withoutParity = word & 0x7FFFFFFFL;
        int ones = Integer.bitCount((int) withoutParity);
        return withoutParity | ((ones % 2 == 0) ? 0x80000000L : 0L);
    }

    /**
     * Assemble a word from its source fields. The parity bit is recomputed so
     * the result has odd parity; {@code corruptParity} flips it again for the
     * dedicated parity-error counterexample.
     */
    public static long assemble(Integer labelOctalValue, int sdi, long data, int ssm, boolean corruptParity) {
        int labelBits = labelOctalValue == null ? 0 : reverseBits8(labelOctalValue & 0xFF);
        long word = (labelBits & 0xFFL)
                | ((long) (sdi & 0x3) << 8)
                | ((data & 0x7FFFFL) << 10)
                | ((long) (ssm & 0x3) << 29);
        word = applyOddParity(word);
        return corruptParity ? (word ^ 0x80000000L) : word;
    }
}
