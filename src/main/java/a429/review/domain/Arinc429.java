package a429.review.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ARINC 429 32-bit word field primitives.
 *
 * Bit numbering follows the ARINC 429 electrical convention: bit 1 is the LSB
 * (first transmitted) and bit 32 is the MSB parity bit. The label occupies bits
 * 1-8 but is *transmitted LSB-first* in the opposite bit sense to the rest of
 * the word, so its human octal label is obtained by reversing the 8 label bits.
 */
public final class Arinc429 {

    public static final String DECODER_VERSION = "a429-decoder-1.0.0";

    private Arinc429() {
    }

    /** Unsigned 32-bit word from a hex string such as {@code 0xE1900083}. */
    public static long parseWord(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw word is null");
        }
        String s = raw.trim().replace("_", "");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.isEmpty() || s.length() > 8 || !s.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("not a 32-bit hex word: " + raw);
        }
        return Long.parseLong(s, 16);
    }

    public static String hex(long word) {
        return String.format("0x%08X", word & 0xFFFFFFFFL);
    }

    /** Extract bits [hi..lo] (1-based ARINC numbering, inclusive) as an unsigned int. */
    public static int bits(long word, int hi, int lo) {
        long mask = mask(hi - lo + 1) << (lo - 1);
        return (int) ((word & mask) >>> (lo - 1));
    }

    private static long mask(int n) {
        return n >= 32 ? 0xFFFFFFFFL : (1L << n) - 1;
    }

    public static int labelRaw(long word) {
        return bits(word, 8, 1);
    }

    public static int sdi(long word) {
        return bits(word, 10, 9);
    }

    public static int data(long word) {
        return bits(word, 29, 11);
    }

    public static int ssm(long word) {
        return bits(word, 31, 30);
    }

    public static int parity(long word) {
        return bits(word, 32, 32);
    }

    /** Reverse the 8 bit positions; bit 1 <-> bit 8. Presentation only. */
    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            if ((b & (1 << i)) != 0) {
                r |= 1 << (7 - i);
            }
        }
        return r & 0xFF;
    }

    /** Octal label value obtained from the bit-reversed label field. */
    public static String octalLabel(long word) {
        return String.format("%03o", reverse8(labelRaw(word)));
    }

    /** Label field bit string in transmitted order, bit 1 (first sent) leftmost. */
    public static String labelBitString(long word) {
        int raw = labelRaw(word);
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append((raw >>> i) & 1);
        }
        return sb.toString();
    }

    /** Odd parity is correct when the total number of ONE bits over all 32 bits is odd. */
    public static boolean oddParityOk(long word) {
        return (Long.bitCount(word & 0xFFFFFFFFL) & 1) == 1;
    }

    public static int correctParityBit(long wordWithoutParity) {
        int ones = Long.bitCount(wordWithoutParity & 0x7FFFFFFFL);
        return (ones & 1) == 1 ? 0 : 1;
    }

    /**
     * SSM meaning per ARINC 429, which differs by data type family.
     * @param dataType BNR / BCD / DISCRETE per dictionary entry
     */
    public static String ssmMeaning(String dataType, int ssm) {
        if ("BNR".equalsIgnoreCase(dataType)) {
            return switch (ssm) {
                case 0 -> "FAILURE_WARNING";
                case 1 -> "NO_COMPUTED_DATA";
                case 2 -> "FUNCTIONAL_TEST";
                default -> "NORMAL";
            };
        }
        // BCD and discrete
        return switch (ssm) {
            case 0 -> "NORMAL";
            case 1 -> "FUNCTIONAL_TEST";
            case 2 -> "NOT_USED";
            default -> "FAILURE_WARNING";
        };
    }

    public static boolean ssmIsNormal(String dataType, int ssm) {
        return "NORMAL".equals(ssmMeaning(dataType, ssm));
    }

    public static Map<String, Object> bitFieldMap(long word) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bits32_1", String.format("%32s", Long.toBinaryString(word & 0xFFFFFFFFL)).replace(' ', '0'));
        m.put("labelRaw", labelRaw(word));
        m.put("labelBitsTransmitted", labelBitString(word));
        m.put("octalLabel", octalLabel(word));
        m.put("sdi", sdi(word));
        m.put("data", data(word));
        m.put("ssm", ssm(word));
        m.put("parityBit", parity(word));
        m.put("parityOdd", oddParityOk(word));
        return m;
    }
}
