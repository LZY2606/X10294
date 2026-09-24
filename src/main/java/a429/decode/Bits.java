package a429.decode;

/**
 * Bit-level helpers for a 32-bit ARINC 429 word.
 *
 * <p>ARINC bit numbering is used throughout: bit 1 is transmitted first and
 * lives in the most-significant bit of the packed {@code long}; bit 32 (parity)
 * is the least-significant bit.</p>
 */
public final class Bits {
    private Bits() { }

    /** Value of a single ARINC-numbered bit (1..32), 0 or 1. */
    public static int bit(long word, int arincBit) {
        return (int) ((word >>> (32 - arincBit)) & 1L);
    }

    /**
     * Inclusive bit field read MSB-first (as transmitted on the data side of
     * the word). {@code high} is the more-significant ARINC bit number.
     */
    public static int fieldMsb(long word, int high, int low) {
        int width = high - low + 1;
        long mask = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1L;
        return (int) ((word >>> (32 - high)) & mask);
    }

    /**
     * Inclusive bit field read LSB-first, i.e. the lowest ARINC bit number is
     * the least-significant value bit (label field convention).
     */
    public static int fieldLsb(long word, int high, int low) {
        int v = 0;
        for (int b = low; b <= high; b++) {
            v |= bit(word, b) << (b - low);
        }
        return v;
    }

    /** Reverse the lowest {@code width} bits of {@code value}. */
    public static int reverse(int value, int width) {
        int r = 0;
        for (int i = 0; i < width; i++) {
            r |= ((value >>> i) & 1) << (width - 1 - i);
        }
        return r;
    }

    /** ARINC 429 uses odd parity: the whole 32-bit word must have even weight. */
    public static boolean oddParityValid(long word) {
        return (Long.bitCount(word & 0xFFFFFFFFL) & 1) == 0;
    }

    /** Force odd parity by adjusting bit 32; returns the corrected 32-bit word. */
    public static long withOddParity(long word) {
        long w = word & 0xFFFFFFFFL;
        if ((Long.bitCount(w) & 1) != 0) {
            w ^= 1L;
        }
        return w;
    }
}
