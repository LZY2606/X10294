package avionics;

/** ARINC 429 32-bit word parsing. Bit 1 of the ARINC word is bit 0 of the int. */
public final class Arinc {

    private Arinc() {}

    public static int reverse8(int b) {
        return Integer.reverse(b & 0xFF) >>> 24;
    }

    public static long reverse32(long w) {
        return Integer.reverse((int) (w & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
    }

    /** Odd parity over the low 31 bits; returns the parity bit value (bit 32). */
    public static int oddParityBit(long word31) {
        int ones = Long.bitCount(word31 & 0x7FFFFFFFL);
        return (ones % 2 == 0) ? 1 : 0;
    }

    public record Parsed(
            long rawBits,      // 32-bit word exactly as recorded
            boolean parityOk,  // odd parity check over all 32 bits
            int labelBits,     // bits 1-8 as transmitted (bit1 = LSB of this field)
            int labelByte,     // label after bit-order correction (bits reversed)
            String labelOctal, // octal display of labelByte; display only, bits unchanged
            int sdi,           // bits 9-10
            long dataField,    // bits 11-29 (19 bits)
            int ssm            // bits 30-31
    ) {}

    /** Parse a 32-bit word. Never fails: bad parity / unknown labels are reported, not rejected. */
    public static Parsed parse(long raw) {
        long w = raw & 0xFFFFFFFFL;
        int parityBit = (int) ((w >>> 31) & 1);
        boolean parityOk = parityBit == oddParityBit(w);
        int labelBits = (int) (w & 0xFF);
        int labelByte = reverse8(labelBits);
        int sdi = (int) ((w >>> 8) & 0x3);
        long data = (w >>> 10) & 0x7FFFFL;
        int ssm = (int) ((w >>> 29) & 0x3);
        return new Parsed(w, parityOk, labelBits, labelByte,
                toOctal(labelByte), sdi, data, ssm);
    }

    /** Octal display of a label byte, e.g. 0x83 -> "203". Display only; underlying bits unchanged. */
    public static String toOctal(int labelByte) {
        return String.format("%03o", labelByte & 0xFF);
    }

    /** Build a word from fields (used by the case-pack generator and tests). */
    public static long compose(int labelByte, int sdi, long data, int ssm) {
        long w = (reverse8(labelByte) & 0xFFL)
                | ((sdi & 0x3L) << 8)
                | ((data & 0x7FFFFL) << 10)
                | ((ssm & 0x3L) << 29);
        w |= ((long) oddParityBit(w)) << 31;
        return toUnsigned(w);
    }

    public static long toUnsigned(long w) {
        return w & 0xFFFFFFFFL;
    }

    /** Sign-extend a two's complement value held in the low `bits` of v. */
    public static long signExtend(long v, int bits) {
        long sign = 1L << (bits - 1);
        long mask = (1L << bits) - 1;
        v &= mask;
        return (v ^ sign) - sign;
    }
}
