package a429;

/** ARINC 429 32-bit word decoding. Raw bits are always preserved; everything here is derived. */
public final class Decoder {

    public record Decoded(long raw, int labelByte, String labelOctal, int sdi, long dataField,
                          int ssm, int parityBit, boolean parityOk) {}

    /** Reverse the 8 label bits: ARINC 429 transmits the label MSB-first, so the
     *  octal label shown to reviewers is derived from the bit-reversed byte.
     *  The underlying bits of the raw word are never changed by this display. */
    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) { r = (r << 1) | ((b >> i) & 1); }
        return r;
    }

    public static Decoded decode(long raw) {
        if (raw < 0 || raw > 0xFFFFFFFFL) throw new IllegalArgumentException("not a 32-bit word: " + raw);
        int labelByte = (int) (raw & 0xFF);
        int sdi = (int) ((raw >> 8) & 0x3);
        long data = (raw >> 10) & 0x7FFFF; // bits 11-29, 19 bits
        int ssm = (int) ((raw >> 29) & 0x3);
        int parity = (int) ((raw >> 31) & 0x1);
        boolean parityOk = (Long.bitCount(raw) & 1) == 1; // odd parity over all 32 bits
        String octal = String.format("%03o", reverse8(labelByte));
        return new Decoded(raw, labelByte, octal, sdi, data, ssm, parity, parityOk);
    }

    /** Build a raw word from fields; parity bit computed for odd parity unless forceParity >= 0. */
    public static long encode(int labelOctal, int sdi, long dataField, int ssm, int forceParity) {
        int labelByte = reverse8(labelOctal); // accepts octal value as int, e.g. 0o203
        long raw = (labelByte & 0xFFL)
                | ((sdi & 0x3L) << 8)
                | ((dataField & 0x7FFFFL) << 10)
                | ((ssm & 0x3L) << 29);
        int parity = forceParity >= 0 ? forceParity : ((Long.bitCount(raw) & 1) == 1 ? 0 : 1);
        return raw | ((long) parity << 31);
    }
}
