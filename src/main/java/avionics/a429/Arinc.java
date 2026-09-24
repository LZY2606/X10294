package avionics.a429;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 32-bit word parsing.
 *
 * Bit layout (bit 1 = first transmitted, LSB of the 32-bit register):
 *   bits 1-8   label (transmitted MSB-of-label first, so the register low byte
 *              is the bit-reversed label number)
 *   bits 9-10  SDI
 *   bits 11-29 data field (19 bits)
 *   bits 30-31 SSM
 *   bit  32    parity (odd)
 *
 * Raw words are always preserved as-is; every interpretation here is derived.
 */
public final class Arinc {

    private Arinc() {}

    public static final int SSM_NORMAL = 0;
    public static final int SSM_NCD = 1;
    public static final int SSM_TEST = 2;
    public static final int SSM_MINUS = 3;

    /** Reverse the 8 bits of a byte. */
    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >> i) & 1);
        }
        return r;
    }

    /** Reverse all 32 bits (used when a source captured the word bit-reversed). */
    public static long reverse32(long w) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | ((w >> i) & 1L);
        }
        return r & 0xFFFFFFFFL;
    }

    /** Label number 0..255 after undoing the on-the-wire bit reversal. */
    public static int labelValue(long raw) {
        return reverse8((int) (raw & 0xFF));
    }

    /** Octal display of the label, e.g. "206". Display only; bits unchanged. */
    public static String labelOctal(long raw) {
        return String.format("%03o", labelValue(raw));
    }

    public static int sdi(long raw) {
        return (int) ((raw >> 8) & 0x3);
    }

    public static long dataField(long raw) {
        return (raw >> 10) & 0x7FFFFL;
    }

    public static int ssm(long raw) {
        return (int) ((raw >> 29) & 0x3);
    }

    public static int parityBit(long raw) {
        return (int) ((raw >> 31) & 0x1);
    }

    /** ARINC 429 uses odd parity across all 32 bits. */
    public static boolean parityOk(long raw) {
        return (Long.bitCount(raw & 0xFFFFFFFFL) & 1) == 1;
    }

    public static String ssmName(int ssm) {
        switch (ssm) {
            case SSM_NORMAL: return "NORMAL";
            case SSM_NCD: return "NCD";
            case SSM_TEST: return "TEST";
            case SSM_MINUS: return "MINUS";
            default: throw new IllegalArgumentException("ssm " + ssm);
        }
    }

    /** SSM states that exclude a word from aggregates (NCD / TEST). */
    public static boolean ssmValidForData(int ssm) {
        return ssm == SSM_NORMAL || ssm == SSM_MINUS;
    }

    /** 19-bit two's-complement BNR magnitude, times the dictionary resolution. */
    public static double bnrValue(long dataField, double resolution) {
        long v = dataField & 0x7FFFFL;
        if ((v & 0x40000L) != 0) {
            v -= 0x80000L;
        }
        return v * resolution;
    }

    /** Result of a BCD decode attempt. Illegal nibbles keep the raw value and a diagnostic. */
    public static final class BcdResult {
        public final boolean ok;
        public final long value;
        public final int badNibblePosition; // -1 when ok; 0 = least significant digit
        public final int badNibbleValue;

        BcdResult(boolean ok, long value, int badNibblePosition, int badNibbleValue) {
            this.ok = ok;
            this.value = value;
            this.badNibblePosition = badNibblePosition;
            this.badNibbleValue = badNibbleValue;
        }
    }

    /** Decode 5 BCD digits from the 19-bit data field (4 full nibbles + 3-bit MSD). */
    public static BcdResult bcdValue(long dataField) {
        long v = dataField & 0x7FFFFL;
        long result = 0;
        long multiplier = 1;
        for (int pos = 0; pos < 4; pos++) {
            int nibble = (int) (v & 0xF);
            if (nibble > 9) {
                return new BcdResult(false, -1, pos, nibble);
            }
            result += nibble * multiplier;
            multiplier *= 10;
            v >>= 4;
        }
        int msd = (int) (v & 0x7); // 3 remaining bits
        result += msd * multiplier;
        return new BcdResult(true, result, -1, -1);
    }

    /** Individual bit states for discrete labels, LSB of data field = bit 11 of word. */
    public static List<int[]> discreteBits(long dataField, int[] positions) {
        List<int[]> out = new ArrayList<>();
        for (int p : positions) {
            out.add(new int[]{p, (int) ((dataField >> p) & 1)});
        }
        return out;
    }
}
