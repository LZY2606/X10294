package a429.codec;

/**
 * ARINC 429 32-bit word layout and bit helpers.
 *
 * Bit numbering follows ARINC convention: bit 1 is the LSB and is transmitted first.
 *   bits 1-8   label (transmitted LSB first), bits 9-10 SDI, bits 11-29 data (19 bits),
 *   bits 30-31 SSM, bit 32 parity (odd parity over all 32 bits).
 *
 * The integer argument always carries the word exactly as received: these functions never
 * mutate the stored representation. Presentation conversions (label octal, bit reversal)
 * live beside, but never overwrite, the raw word.
 */
public final class Words {
    public static final int LABEL_BITS = 8;
    public static final int SDI_BITS = 2;
    public static final int DATA_BITS = 19;
    public static final int SSM_BITS = 2;

    private Words() {}

    /** Label field, bits 1-8, in wire order. */
    public static int labelField(int word) {
        return word & 0xFF;
    }

    public static int sdi(int word) {
        return (word >>> 8) & 0x3;
    }

    public static int data(int word) {
        return (word >>> 10) & 0x7FFFF;
    }

    public static int ssm(int word) {
        return (word >>> 29) & 0x3;
    }

    public static int parityBit(int word) {
        return (word >>> 31) & 0x1;
    }

    /** Odd parity: the 32-bit word must contain an odd number of set bits. */
    public static boolean oddParityValid(int word) {
        return (Integer.bitCount(word) & 1) == 1;
    }

    /** Reverse the lowest {@code bits} of a value (bit 0 swaps with bit n-1, ...). */
    public static int reverseLow(int value, int bits) {
        int result = 0;
        for (int i = 0; i < bits; i++) {
            if ((value & (1 << i)) != 0) {
                result |= 1 << (bits - 1 - i);
            }
        }
        return result;
    }

    /**
     * Canonical ARINC label number: the label field is transmitted LSB-first, so the label
     * number is the 8-bit field reversed, shown in octal (e.g. {@code 203}). Presentation only.
     */
    public static int labelNumber(int word) {
        return reverseLow(labelField(word), 8);
    }

    public static String labelOctal(int word) {
        return String.format("%03o", labelNumber(word));
    }

    /** Reverse the bit order of the whole 32-bit word (the bit-reversed capture counter-case). */
    public static int reverseWord(int word) {
        return reverseLow(word, 32);
    }

    /** Set bit 32 so the word carries odd parity. */
    public static int withOddParity(int wordWithoutParity) {
        int bitCount = Integer.bitCount(wordWithoutParity & 0x7FFFFFFF);
        int result = wordWithoutParity & 0x7FFFFFFF;
        if ((bitCount & 1) == 0) {
            result |= 1 << 31;
        }
        return result;
    }
}
