package a429.decode;

/**
 * Builds capture-realistic 32-bit words for the bundled offline case pack.
 * Fields are placed at ARINC bit positions; odd parity is set on bit 32.
 */
public final class WordBuilder {

    private WordBuilder() {
    }

    public static int word(String displayOctal, int sdi, int data, int ssm, boolean forceBadParity) {
        int display = Integer.parseInt(displayOctal, 8);
        int labelField = (Integer.reverse(display) >>> 24) & 0xFF;
        int word = 0;
        word |= labelField & 0xFF;
        word |= (sdi & 0x3) << 8;
        word |= (data & 0x7FFFF) << 10;
        word |= (ssm & 0x3) << 29;
        word |= parityBit(word);
        if (forceBadParity) {
            word ^= 1 << 31;
        }
        return word;
    }

    public static String hex(String displayOctal, int sdi, int data, int ssm, boolean badParity) {
        return String.format("%08X", word(displayOctal, sdi, data, ssm, badParity));
    }

    private static int parityBit(int withoutParity) {
        int count = Integer.bitCount(withoutParity & 0x7FFFFFFF);
        return (count % 2 == 0) ? (1 << 31) : 0;
    }
}
