package avionics;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 32-bit word layout (bit 1 = LSB of the transmitted word):
 *   bits 1-8   label (transmitted LSB-first; octal label = bit-reversed byte)
 *   bits 9-10  SDI
 *   bits 11-29 data field (19 bits)
 *   bits 30-31 SSM
 *   bit  32    parity (odd)
 *
 * The octal label display never changes the underlying bits; it is a pure
 * presentation of the same byte. BNR/BCD/discrete interpretation is decided
 * by the dictionary version, never by the word itself.
 */
public final class Arinc {

    private Arinc() {}

    public static long parseRaw(String hex) {
        long w = Long.parseLong(hex.replace("0x", ""), 16);
        if (w < 0 || w > 0xFFFFFFFFL) throw new IllegalArgumentException("not a 32-bit word: " + hex);
        return w;
    }

    public static String hex(long w) {
        return String.format("%08X", w & 0xFFFFFFFFL);
    }

    /** Raw label byte as it sits in bits 1-8. */
    public static int labelByte(long w) {
        return (int) (w & 0xFF);
    }

    /** Octal label per ARINC convention: the label byte bit-reversed, shown in octal. */
    public static String labelOctal(long w) {
        return String.format("%03o", reverse8(labelByte(w)));
    }

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >> i) & 1);
        }
        return r;
    }

    /** Reverse all 32 bits — used by the bit-order-reversal counterexample. */
    public static long reverse32(long w) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | ((w >> i) & 1L);
        }
        return r & 0xFFFFFFFFL;
    }

    public static int sdi(long w) {
        return (int) ((w >> 8) & 0x3);
    }

    public static long dataField(long w) {
        return (w >> 10) & 0x7FFFFL; // bits 11-29
    }

    public static int ssm(long w) {
        return (int) ((w >> 29) & 0x3);
    }

    /** Odd parity over all 32 bits: ok when the total number of 1-bits is odd. */
    public static boolean parityOk(long w) {
        return Long.bitCount(w & 0xFFFFFFFFL) % 2 == 1;
    }

    public static long withParity(long wWithoutParity) {
        long w = wWithoutParity & 0x7FFFFFFFL;
        if (Long.bitCount(w) % 2 == 0) w |= 0x80000000L;
        return w;
    }

    /**
     * SSM validity for a data type. For BNR, SSM 0/3 are operational
     * (3 = sign matrix "normal" is 0 for plus/no-computed... per ARINC:
     * 0 = normal/plus, 1 = no computed data, 2 = functional test, 3 = normal/minus
     * for BNR; for BCD 0 = normal, others non-operational). We treat SSM 1 and 2
     * as non-operational for both, which is what the "SSM invalid" case exercises.
     */
    public static boolean ssmOperational(int ssm) {
        return ssm == 0 || ssm == 3;
    }

    /** BNR decode: bit 29 of the word (data MSB) is the sign; two's complement over the data field. */
    public static double decodeBnr(long w, double scale) {
        long data = dataField(w);
        long value = data;
        if ((data & 0x40000L) != 0) { // bit 29 set -> negative
            value = data - 0x80000L;
        }
        return value * scale;
    }

    public static final class BcdResult {
        public final Long value;       // null when an illegal digit is present
        public final String diagnostic; // non-null when illegal
        BcdResult(Long value, String diagnostic) {
            this.value = value;
            this.diagnostic = diagnostic;
        }
    }

    /** BCD decode of the data field, most significant nibble first. Illegal digits stay raw. */
    public static BcdResult decodeBcd(long w, int digits) {
        long data = dataField(w);
        long value = 0;
        List<Integer> nibbles = new ArrayList<>();
        for (int i = digits - 1; i >= 0; i--) {
            int shift = 1 + i * 4; // data field starts at bit 11 -> shift 10 + ... aligned to nibbles
            int nibble = (int) ((data >> (i * 4)) & 0xF);
            nibbles.add(nibble);
            if (nibble > 9) {
                return new BcdResult(null,
                        "illegal BCD digit 0x" + Integer.toHexString(nibble).toUpperCase()
                                + " at digit position " + (digits - i) + "; raw data field 0x"
                                + Long.toHexString(data).toUpperCase() + " kept in place");
            }
            value = value * 10 + nibble;
        }
        return new BcdResult(value, null);
    }

    /** Build a word from fields (parity computed). */
    public static long build(int labelOctal, int sdi, long data, int ssm) {
        int labelByte = reverse8(labelOctal & 0xFF);
        long w = ((long) (ssm & 0x3) << 29) | ((data & 0x7FFFFL) << 10) | ((long) (sdi & 0x3) << 8) | labelByte;
        return withParity(w);
    }
}
