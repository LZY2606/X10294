package avionics.decode;

/**
 * ARINC 429 32-bit word bit-level utilities.
 *
 * Wire layout (bit 1 transmitted first):
 *   bits 1-8   label (transmitted LSB-of-octal-digit-group first; the received
 *              low byte is the bit-reversal of the numeric label)
 *   bits 9-10  SDI
 *   bits 11-29 data field (19 bits; BNR two's-complement incl. sign at bit 29,
 *              BCD nibbles, or discrete bits)
 *   bits 30-31 SSM
 *   bit  32    odd parity
 */
public final class Arinc {
    private Arinc() {}

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) r = (r << 1) | ((b >>> i) & 1);
        return r;
    }

    public static long reverse32(long w) {
        return Integer.reverse((int) w) & 0xFFFFFFFFL;
    }

    /** Bit-exact parse of one 32-bit word. Dictionary-independent. */
    public record Parsed(
            boolean parityOk,
            int label,
            String labelOctal,
            int sdi,
            int ssm,
            long dataField,
            String bitOrder) {}

    /** Parse assuming normal bit order, or assuming the source word was captured bit-reversed. */
    public static Parsed parse(long word, boolean reversed) {
        long w = (reversed ? reverse32(word) : word) & 0xFFFFFFFFL;
        boolean parityOk = (Long.bitCount(w) & 1) == 1; // odd parity
        int label = reverse8((int) (w & 0xFF));
        String octal = String.format("%03o", label);
        int sdi = (int) ((w >>> 8) & 0x3);
        long data = (w >>> 10) & 0x7FFFFL; // bits 11..29
        int ssm = (int) ((w >>> 29) & 0x3);
        return new Parsed(parityOk, label, octal, sdi, ssm, data, reversed ? "reversed" : "normal");
    }

    /** Compose a word; used by tests and the case-pack generator. */
    public static long compose(int label, int sdi, long dataField, int ssm) {
        long w = ((long) reverse8(label) & 0xFF)
                | ((long) (sdi & 3) << 8)
                | ((dataField & 0x7FFFFL) << 10)
                | ((long) (ssm & 3) << 29);
        if ((Long.bitCount(w) & 1) == 0) w |= 1L << 31; // force odd parity
        return w & 0xFFFFFFFFL;
    }
}
