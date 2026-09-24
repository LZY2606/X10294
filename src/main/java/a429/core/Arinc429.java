package a429.core;

/**
 * ARINC 429 32-bit word bit-field extraction.
 *
 * Layout (bit 1 = first transmitted = Java long bit 0):
 *   bits 1-8   label byte (bit 1 is the MSB of the label value)
 *   bits 9-10  SDI
 *   bits 11-29 data field (19 bits)
 *   bits 30-31 SSM
 *   bit  32    odd parity over all 32 bits
 *
 * Extraction is deterministic and interpretation-free: engineering meaning
 * is only attached later by the dictionary-versioned Interpreter.
 */
public final class Arinc429 {

    public static final long WORD_MASK = 0xFFFFFFFFL;
    public static final long DATA_MASK = 0x7FFFFL; // 19 bits
    public static final long BNR_SIGN = 0x40000L;  // bit 29 of the word

    private Arinc429() {}

    /** Bit fields extracted from a raw 32-bit word. Raw bits are never altered. */
    public record Fields(
            long rawWord,
            boolean parityOk,
            String labelOctal,
            int labelValue,
            int sdi,
            int ssm,
            long data29) {}

    public static int reverse8(int v) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((v >> i) & 1);
        }
        return r;
    }

    public static long reverse32(long v) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | ((v >> i) & 1L);
        }
        return r & WORD_MASK;
    }

    /** Octal text is a display convention only; it never changes the underlying bits. */
    public static String octalText(int labelValue) {
        return String.format("%03o", labelValue & 0xFF);
    }

    public static boolean oddParityOk(long raw) {
        return Long.bitCount(raw & WORD_MASK) % 2 == 1;
    }

    public static Fields extract(long raw) {
        raw &= WORD_MASK;
        int labelByte = (int) (raw & 0xFF);
        int label = reverse8(labelByte);
        int sdi = (int) ((raw >> 8) & 0x3);
        long data29 = (raw >> 10) & DATA_MASK;
        int ssm = (int) ((raw >> 29) & 0x3);
        return new Fields(raw, oddParityOk(raw), octalText(label), label, sdi, ssm, data29);
    }

    /** Builds a word from logical fields; used by the case pack to pin raw representations. */
    public static long buildWord(String labelOctal, int sdi, long data29, int ssm, boolean forceBadParity) {
        int label = Integer.parseInt(labelOctal, 8);
        long w = reverse8(label) & 0xFFL;
        w |= (long) (sdi & 0x3) << 8;
        w |= (data29 & DATA_MASK) << 10;
        w |= (long) (ssm & 0x3) << 29;
        if (Long.bitCount(w) % 2 == 0) {
            w |= 1L << 31;
        }
        if (forceBadParity) {
            w ^= 1L << 31;
        }
        return w & WORD_MASK;
    }

    /** Two's-complement BNR over the 19-bit data field, scaled by the dictionary entry. */
    public static double decodeBnr(long data29, double scale, double offset) {
        long v = data29 & DATA_MASK;
        if ((v & BNR_SIGN) != 0) {
            v -= (DATA_MASK + 1);
        }
        return v * scale + offset;
    }

    public record BcdResult(boolean ok, long value, int badNibbleIndex, int badNibble) {}

    /** Five packed BCD digits, nibble 0 = least significant digit. Illegal nibbles are reported, not repaired. */
    public static BcdResult decodeBcd(long data29) {
        long value = 0;
        long mult = 1;
        for (int i = 0; i < 5; i++) {
            int nib = (int) ((data29 >> (i * 4)) & 0xF);
            if (nib > 9) {
                return new BcdResult(false, -1, i, nib);
            }
            value += nib * mult;
            mult *= 10;
        }
        return new BcdResult(true, value, -1, -1);
    }
}
