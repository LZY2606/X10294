package av429.domain;

/**
 * ARINC 429 bit layout helpers.
 *
 * A transmitted ARINC 429 word is 32 bits, numbered 1..32 (1 is transmitted
 * first). Bit 1 is the least significant bit of label octet digit 1.
 *
 * This implementation stores a word in a Java {@code long} where
 * {@code bit n} (ARINC numbering) maps to integer index {@code n-1}:
 *
 *   label  : bits 1..8   (ARINC 429 transmits label MSB last; the dictionary
 *                         key uses the value AFTER reversing the 8 label bits)
 *   SDI    : bits 9..10
 *   data   : bits 11..29
 *   SSM    : bits 30..31
 *   parity : bit 32      (odd parity over all 32 bits)
 *
 * Nothing in the decode path mutates the raw word: the "octal display" is a
 * separate view over the same 8 label bits.
 */
public final class Fields {

    private Fields() {
    }

    /** Raw 8 bits as they sit in bit positions 1..8 (transmission order view). */
    public static int labelBitsRaw(long word) {
        return (int) (word & 0xFFL);
    }

    /** Reverse the 8 label bits to obtain the dictionary/ARINC label value. */
    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (b & 1);
            b >>= 1;
        }
        return r & 0xFF;
    }

    /** Dictionary label value (0..255) from the 32-bit word. */
    public static int labelValue(long word) {
        return reverse8(labelBitsRaw(word));
    }

    /** Octal string of the dictionary label value, e.g. 80 -> "120". */
    public static String labelOctal(long word) {
        return String.format("%03o", labelValue(word));
    }

    /**
     * Octal string of the bits exactly as transmitted in positions 1..8.
     * This is only a display alternative; it never changes the underlying bits.
     */
    public static String labelOctalTransmission(long word) {
        return String.format("%03o", labelBitsRaw(word));
    }

    public static int sdi(long word) {
        return (int) ((word >>> 8) & 0x3L);
    }

    public static int ssm(long word) {
        return (int) ((word >>> 29) & 0x3L);
    }

    public static int dataBits(long word) {
        return (int) ((word >>> 10) & 0x7FFFFL);
    }

    public static int parityBit(long word) {
        return (int) ((word >>> 31) & 0x1L);
    }

    public static int popcount32(long word) {
        return Integer.bitCount((int) (word & 0xFFFFFFFFL));
    }

    /** ARINC 429 uses odd parity: the 32-bit word must contain an odd number of ones. */
    public static boolean parityOk(long word) {
        return (popcount32(word) & 1) == 1;
    }

    /** Set bit 32 so the word has odd parity; returns a new word, never mutates input. */
    public static long withOddParity(long word) {
        long w = word & 0x7FFFFFFFL;
        if ((popcount32(w) & 1) == 0) {
            w |= 1L << 31;
        }
        return w;
    }

    /** Flip bit 32, producing a bad-parity word from a good one and vice versa. */
    public static long flipParity(long word) {
        return word ^ (1L << 31);
    }

    /** Bit-reverse all 32 bits (reversed arrival order / endian error scenario). */
    public static long reverse32(long word) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | (word & 1L);
            word >>>= 1;
        }
        return r & 0xFFFFFFFFL;
    }

    /** Read {@code len} contiguous data bits starting at ARINC bit position {@code startBit}. */
    public static int slice(long word, int startBit, int len) {
        int shift = startBit - 1;
        long mask = len >= 32 ? 0xFFFFFFFFL : ((1L << len) - 1L);
        return (int) ((word >>> shift) & mask);
    }

    public static boolean getBit(long word, int arincBitNumber) {
        return ((word >>> (arincBitNumber - 1)) & 1L) == 1L;
    }

    /** Render the word as 32 characters, ARINC bit 1 first. */
    public static String bitStringArincOrder(long word) {
        StringBuilder sb = new StringBuilder(32);
        for (int n = 1; n <= 32; n++) {
            sb.append(getBit(word, n) ? '1' : '0');
        }
        return sb.toString();
    }
}
