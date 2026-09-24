package arinc;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 32-bit word decoding.
 *
 * Storage convention: the 32-bit word is held in an int where bit 0
 * corresponds to ARINC bit 1 (first transmitted bit) and bit 31 to
 * ARINC bit 32 (odd parity bit).
 *
 * Layout: bits 1-8 label (MSB of octal label transmitted first),
 * 9-10 SDI, 11-29 data, 30-31 SSM, 32 parity.
 */
public final class Arinc429 {

    private Arinc429() {}

    /** Raw decode result. Original representation is always preserved. */
    public record Decoded(
            long word,
            boolean parityOk,
            int parityBit,
            String labelOctal,
            int labelRaw,     // low byte as received (before bit reversal)
            int sdi,
            long dataField,   // 19 bits
            int ssm,
            String diagnostic) {}

    /** Candidate decode when bit order reversal is suspected. */
    public record DecodeCandidate(Decoded decoded, boolean bitReversed, String rationale) {}

    public static int bitReverse8(int v) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((v >> i) & 1);
        }
        return r;
    }

    public static long bitReverse32(long w) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | ((w >> i) & 1);
        }
        return r;
    }

    public static String toOctalLabel(int labelByte) {
        return String.format("%03o", labelByte & 0xFF);
    }

    public static Decoded decode(long word) {
        long w = word & 0xFFFFFFFFL;
        int pop = Long.bitCount(w);
        boolean parityOk = (pop % 2) == 1; // odd parity
        int parityBit = (int) ((w >> 31) & 1);
        int labelRaw = (int) (w & 0xFF);
        int labelByte = bitReverse8(labelRaw);
        String labelOctal = toOctalLabel(labelByte);
        int sdi = (int) ((w >> 8) & 0x3);
        long data = (w >> 10) & 0x7FFFFL;
        int ssm = (int) ((w >> 29) & 0x3);
        List<String> diag = new ArrayList<>();
        if (!parityOk) diag.add("PARITY_FAIL");
        return new Decoded(w, parityOk, parityBit, labelOctal, labelRaw, sdi, data, ssm,
                String.join(",", diag));
    }

    /**
     * Produce decode candidates. The as-received decode is always first.
     * If the as-received label is not in the dictionary but the bit-reversed
     * word yields a known label, a bit-reversal candidate is added; both are
     * kept (no irreversible cleaning at import).
     */
    public static List<DecodeCandidate> candidates(long word, Dictionary dict) {
        List<DecodeCandidate> out = new ArrayList<>();
        Decoded primary = decode(word);
        out.add(new DecodeCandidate(primary, false, "as-received"));
        if (!dict.hasLabel(primary.labelOctal())) {
            long rev = bitReverse32(word);
            Decoded alt = decode(rev);
            if (dict.hasLabel(alt.labelOctal())) {
                out.add(new DecodeCandidate(alt, true,
                        "bit-order reversal suspected: label " + primary.labelOctal()
                                + " unknown, reversed label " + alt.labelOctal() + " in dictionary"));
            }
        }
        return out;
    }

    /** SSM meaning for BNR words. */
    public static String ssmStatusBnr(int ssm) {
        return switch (ssm) {
            case 0 -> "FAILURE_WARNING";
            case 1 -> "NO_COMPUTED_DATA";
            case 2 -> "FUNCTIONAL_TEST";
            case 3 -> "NORMAL";
            default -> "UNKNOWN";
        };
    }

    /** SSM meaning for BCD words (also carries sign). */
    public static String ssmStatusBcd(int ssm) {
        return switch (ssm) {
            case 0 -> "PLUS_NORTH_EAST_RIGHT_TO_ABOVE";
            case 1 -> "NO_COMPUTED_DATA";
            case 2 -> "FUNCTIONAL_TEST";
            case 3 -> "MINUS_SOUTH_WEST_LEFT_FROM_BELOW";
            default -> "UNKNOWN";
        };
    }

    public static boolean ssmValid(int ssm) {
        return ssm == 3 || ssm == 0; // normal, or plus for BCD; NCD/test are not aggregateable
    }

    /** BNR two's-complement interpretation of the 19-bit data field. */
    public static double bnrValue(long dataField, int sigBits, double resolution) {
        long mask = (1L << sigBits) - 1;
        long raw = dataField & mask;
        long signBit = 1L << (sigBits - 1);
        long signed = (raw & signBit) != 0 ? raw - (1L << sigBits) : raw;
        return signed * resolution;
    }

    /**
     * BCD interpretation: 4 nibbles (digits) from the low 16 bits of the data
     * field. Illegal nibbles (>9) are reported, value stays unconverted.
     */
    public static BcdResult bcdValue(long dataField) {
        int[] digits = new int[4];
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int nib = (int) ((dataField >> (i * 4)) & 0xF);
            digits[3 - i] = nib;
            if (nib > 9) bad.add("digit" + (3 - i) + "=0x" + Integer.toHexString(nib).toUpperCase());
        }
        if (!bad.isEmpty()) {
            return new BcdResult(false, 0, digits, "BCD_ILLEGAL_DIGIT:" + String.join(",", bad));
        }
        int value = digits[0] * 1000 + digits[1] * 100 + digits[2] * 10 + digits[3];
        return new BcdResult(true, value, digits, "");
    }

    public record BcdResult(boolean ok, int value, int[] digits, String diagnostic) {}
}
