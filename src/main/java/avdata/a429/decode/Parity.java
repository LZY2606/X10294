package avdata.a429.decode;

/** ARINC 429 uses odd parity over the full 32-bit word (bit 32 = parity). */
public final class Parity {
    private Parity() {}

    /** True when the word already carries correct odd parity. */
    public static boolean isValid(int word) {
        return Integer.bitCount(word) % 2 == 1;
    }

    /** The parity value bit 32 must have for this word to be odd. */
    public static int expectedParityBit(int wordWithoutParity) {
        int ones = Integer.bitCount(wordWithoutParity & 0x7FFFFFFF);
        return ones % 2 == 0 ? 1 : 0;
    }

    /** Returns the word with bit 32 set so odd parity holds; underlying bits 1..31 are untouched. */
    public static int withOddParity(int word) {
        int repaired = word & 0x7FFFFFFF;
        return repaired | (expectedParityBit(repaired) << 31);
    }
}
