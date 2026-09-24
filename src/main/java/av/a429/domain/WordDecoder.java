package av.a429.domain;

/**
 * ARINC 429 32-bit word decoder.
 *
 * Bit layout of the stored 32-bit word (bit 31 .. bit 0):
 *   bit 31      : parity (odd parity over all 32 bits)
 *   bits 30-29  : SSM
 *   bits 28-10  : data field (19 bits)
 *   bits 9-8    : SDI
 *   bits 7-0    : label
 *
 * The label is conventionally displayed in octal; the octal display never
 * changes the underlying bits. A word captured in wire order needs a full
 * 32-bit reversal before field extraction; both interpretations are kept
 * as candidates by the review layer.
 */
public final class WordDecoder {

    public enum BitOrder { NORMAL, REVERSED }

    public record Decoded(long raw, BitOrder order, int labelBits, String labelOctal,
                          int sdi, int ssm, long dataField, boolean parityOk) {
        public String rawHex() {
            return String.format("%08X", raw);
        }
    }

    private WordDecoder() {}

    public static long reverse32(long w) {
        long r = 0;
        for (int i = 0; i < 32; i++) {
            if (((w >>> i) & 1L) == 1L) {
                r |= 1L << (31 - i);
            }
        }
        return r & 0xFFFFFFFFL;
    }

    public static Decoded decode(long rawWord, BitOrder order) {
        long w = rawWord & 0xFFFFFFFFL;
        if (order == BitOrder.REVERSED) {
            w = reverse32(w);
        }
        int label = (int) (w & 0xFFL);
        int sdi = (int) ((w >>> 8) & 0x3L);
        long data = (w >>> 10) & 0x7FFFFL;
        int ssm = (int) ((w >>> 29) & 0x3L);
        boolean parityOk = (Long.bitCount(w) & 1) == 1;
        return new Decoded(rawWord & 0xFFFFFFFFL, order, label, toOctal(label), sdi, ssm, data, parityOk);
    }

    /** Octal display of the label byte; display only, bits are untouched. */
    public static String toOctal(int label) {
        return String.format("%03o", label & 0xFF);
    }

    /** BNR: 19-bit two's complement, sign in data bit 18 (word bit 29). */
    public static double decodeBnr(long dataField, double scale) {
        long mag = dataField & 0x7FFFFL;
        if ((mag & (1L << 18)) != 0) {
            mag -= (1L << 19);
        }
        return mag * scale;
    }

    public record BcdResult(String digits, boolean illegal, Double value) {}

    /** BCD: four 4-bit nibbles plus a 3-bit top digit; nibbles > 9 are illegal. */
    public static BcdResult decodeBcd(long dataField, double scale) {
        StringBuilder digits = new StringBuilder();
        boolean illegal = false;
        int top = (int) ((dataField >>> 16) & 0x7L);
        digits.append((char) ('0' + top));
        for (int i = 3; i >= 0; i--) {
            int nib = (int) ((dataField >>> (i * 4)) & 0xFL);
            if (nib > 9) {
                illegal = true;
                digits.append('?');
            } else {
                digits.append((char) ('0' + nib));
            }
        }
        Double value = illegal ? null : Long.parseLong(digits.toString()) * scale;
        return new BcdResult(digits.toString(), illegal, value);
    }

    /** SSM 01 (NCD) and 10 (functional test) are invalid for data aggregation. */
    public static boolean ssmValidForData(int ssm) {
        return ssm == 0 || ssm == 3;
    }

    public static String ssmName(int ssm) {
        return switch (ssm) {
            case 0 -> "NORMAL(+)";
            case 1 -> "NCD";
            case 2 -> "TEST";
            default -> "NORMAL(-)";
        };
    }
}
