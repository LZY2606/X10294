package a429.decode;

/**
 * ARINC 429 bit layout over a captured 32-bit word.
 *
 * ARINC bit 1 (first transmitted) is bit position 0 of the integer (LSB),
 * so field extraction reads integer positions bitN-1.
 */
public final class Bits429 {

    public static final int LABEL_LO = 1;   // bits 1..8
    public static final int LABEL_HI = 8;
    public static final int SDI_LO = 9;     // bits 9..10
    public static final int SDI_HI = 10;
    public static final int DATA_LO = 11;   // bits 11..29
    public static final int DATA_HI = 29;
    public static final int SSM_LO = 30;    // bits 30..31
    public static final int SSM_HI = 31;
    public static final int PARITY_BIT = 32;

    private Bits429() {
    }

    public static int field(int word, int arincLow, int arincHigh) {
        int low = arincLow - 1;
        int high = arincHigh - 1;
        int mask = (arincHigh - arincLow + 1 == 32) ? -1
                : ((1 << (high - low + 1)) - 1);
        return (word >>> low) & mask;
    }

    public static int labelField(int word) {
        return field(word, LABEL_LO, LABEL_HI);
    }

    public static int sdi(int word) {
        return field(word, SDI_LO, SDI_HI);
    }

    public static int dataBits(int word) {
        return field(word, DATA_LO, DATA_HI);
    }

    public static int ssm(int word) {
        return field(word, SSM_LO, SSM_HI);
    }

    public static boolean parityBit(int word) {
        return ((word >>> (PARITY_BIT - 1)) & 1) == 1;
    }

    /** Odd parity: total count of set bits across all 32 bits is odd. */
    public static boolean oddParityValid(int word) {
        return Integer.bitCount(word & 0xFFFFFFFF) % 2 == 1;
    }

    /** Reverse the order of all 32 captured bits. */
    public static int reverse32(int word) {
        return Integer.reverse(word);
    }

    /**
     * The 8 label bits are transmitted LSB first. Showing the label as octal
     * reverses only the label-field bits for display; the stored wire bits are
     * never changed.
     */
    public static String labelOctal(int labelField) {
        int display = Integer.reverse(labelField & 0xFF) >>> 24;
        return String.format("%03o", display);
    }

    public static int dataBit(int word, int arincBit) {
        return (word >>> (arincBit - 1)) & 1;
    }
}
