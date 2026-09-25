package avdata.a429.decode;

/**
 * ARINC 429 word bit helpers. Bits are numbered 1..32 in transmission order.
 * Bit 1 is the label LSB / first transmitted bit; bit 32 is the parity bit.
 */
public final class Bits {
    private Bits() {}

    /** Extract an inclusive bit range (1-based, transmission order) from a 32-bit word. */
    public static int field(int word, int lsb, int msb) {
        if (lsb < 1 || msb > 32 || lsb > msb) {
            throw new IllegalArgumentException("bad range " + lsb + ".." + msb);
        }
        int width = msb - lsb + 1;
        long mask = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1L;
        return (int) ((word >>> (lsb - 1)) & mask);
    }

    public static int bit(int word, int n) {
        return (word >>> (n - 1)) & 1;
    }

    /** Reverse the order of all 32 bits (bit i <-> bit 33-i). */
    public static int reverse32(int word) {
        return Integer.reverse(word);
    }

    /** Reverse the 8 label bits, which is how equipment stores the human octal label. */
    public static int reverse8(int value) {
        int v = value & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r |= ((v >>> i) & 1) << (7 - i);
        }
        return r;
    }

    public static int popCount(int word) {
        return Integer.bitCount(word & 0xFFFFFFFF) == 0 ? 0 : Integer.bitCount(word);
    }

    public static String octal3(int labelBits) {
        return String.format("%03o", labelBits & 0xFF);
    }

    public static String hex8(int word) {
        return String.format("%08X", word);
    }

    public static String bin32(int word) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 32; i >= 1; i--) {
            sb.append(bit(word, i));
        }
        return sb.toString();
    }
}
