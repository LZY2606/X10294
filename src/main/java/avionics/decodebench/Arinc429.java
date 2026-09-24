package avionics.decodebench;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 32-bit word codec.
 *
 * Bit numbering follows the spec: bit 1 is transmitted first.
 *   bits 1-8   label (transmitted MSB-first, so the on-wire byte is the
 *              bit-reversal of the octal label value)
 *   bits 9-10  SDI
 *   bits 11-29 data field (bit 29 is the BNR sign bit)
 *   bits 30-31 SSM
 *   bit 32     parity (odd)
 *
 * Decoding never mutates the raw word; every derived value carries its
 * unit / coordinate basis from the dictionary entry that produced it.
 */
public final class Arinc429 {

    private Arinc429() {}

    /** Extract the raw on-wire label byte (bits 1-8). */
    public static int labelByte(long word) {
        return (int) (word & 0xFF);
    }

    /** Decode the octal label: reverse the on-wire byte. */
    public static int label(long word) {
        return reverse8(labelByte(word));
    }

    public static String labelOctal(long word) {
        return String.format("%03o", label(word));
    }

    public static int sdi(long word) {
        return (int) ((word >> 8) & 0x3);
    }

    public static int ssm(long word) {
        return (int) ((word >> 29) & 0x3);
    }

    /** Data field, bits 11-29 (19 bits, includes BNR sign at bit 29). */
    public static int dataField(long word) {
        return (int) ((word >> 10) & 0x7FFFF);
    }

    public static boolean parityBit(long word) {
        return ((word >> 31) & 1) == 1;
    }

    /** Odd parity check over all 32 bits. */
    public static boolean parityOk(long word) {
        return (Long.bitCount(word & 0xFFFFFFFFL) & 1) == 1;
    }

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >> i) & 1);
        }
        return r;
    }

    /** Build a word from logical fields; parity computed as odd. */
    public static long encode(int labelOctal, int sdi, int dataField, int ssm) {
        long w = reverse8(labelOctal) & 0xFFL;
        w |= (long) (sdi & 0x3) << 8;
        w |= (long) (dataField & 0x7FFFF) << 10;
        w |= (long) (ssm & 0x3) << 29;
        if ((Long.bitCount(w) & 1) == 0) {
            w |= 1L << 31;
        }
        return w;
    }

    /** Flip the parity bit to manufacture a parity error. */
    public static long withBadParity(long word) {
        return word ^ (1L << 31);
    }

    /**
     * BNR decode. Sign is bit 29 of the word = bit 18 of the data field.
     * Negative values are two's complement over the 19-bit field.
     */
    public static double bnr(int dataField, double resolution, double offset) {
        long magnitude;
        boolean negative = (dataField & 0x40000) != 0;
        if (negative) {
            magnitude = (~dataField & 0x7FFFF) + 1;
        } else {
            magnitude = dataField & 0x7FFFF;
        }
        double v = magnitude * resolution + offset;
        return negative ? -v : v;
    }

    public record BcdResult(boolean ok, double value, List<Integer> illegalDigits) {}

    /**
     * BCD decode of the data field (nibbles from bit 11 upward, 5 nibbles
     * covering bits 11-29 with the top nibble masked to 3 bits + sign).
     * Any nibble &gt; 9 is an illegal BCD digit: the value is reported as
     * unconvertible and must stay in raw form with a diagnostic.
     */
    public static BcdResult bcd(int dataField) {
        List<Integer> illegal = new ArrayList<>();
        long value = 0;
        long place = 1;
        for (int n = 0; n < 5; n++) {
            int width = (n == 4) ? 3 : 4;
            int mask = (1 << width) - 1;
            int digit = (dataField >> (n * 4)) & mask;
            if (digit > 9) {
                illegal.add(digit);
            } else {
                value += (long) digit * place;
            }
            place *= 10;
        }
        boolean negative = (dataField & 0x40000) != 0;
        return new BcdResult(illegal.isEmpty(), negative ? -value : value, illegal);
    }

    public static String ssmName(int ssm) {
        return switch (ssm) {
            case 0 -> "PLUS/NORTH/EAST/RIGHT/TO/ABOVE";
            case 1 -> "NO_COMPUTED_DATA";
            case 2 -> "FUNCTIONAL_TEST";
            case 3 -> "MINUS/SOUTH/WEST/LEFT/FROM/BELOW";
            default -> "UNKNOWN";
        };
    }

    /** SSM states 1 (NCD) and 2 (functional test) are invalid for normal ops. */
    public static boolean ssmOperational(int ssm) {
        return ssm == 0 || ssm == 3;
    }
}
