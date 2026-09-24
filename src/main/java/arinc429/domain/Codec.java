package arinc429.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure ARINC 429 word codec. No I/O, no database.
 *
 * Bit convention used everywhere in this tool:
 *  - the 32-bit word is held in an {@code int} where integer bit (n-1) is
 *    ARINC bit n (bit 1 is transmitted first and is the LSB of the word);
 *  - bits 1..8   label (transmitted LSB first, reversed for display);
 *  - bits 9..10  SDI;
 *  - bits 11..29 19-bit data field;
 *  - bits 30..31 SSM;
 *  - bit 32      parity (odd parity over all 32 bits).
 */
public final class Codec {

    public static final int LABEL_MASK = 0xFF;
    public static final int SDI_MASK = 0x03;
    public static final int DATA_MASK = 0x7FFFF; // 19 bits
    public static final int SSM_MASK = 0x03;

    private Codec() {
    }

    /** Reverse the eight label bits: the wire label is LSB-first. */
    public static int reverse8(int value) {
        int v = value & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>>= 1;
        }
        return r & 0xFF;
    }

    /** Reverse all 32 bit positions (the "bit order reversal" fault). */
    public static int reverse32(int value) {
        int r = 0;
        for (int i = 0; i < 32; i++) {
            r = (r << 1) | (value & 1);
            value >>>= 1;
        }
        return r;
    }

    /** Odd parity over all 32 bits is valid when the bit count is odd. */
    public static boolean parityOk(int raw) {
        return Integer.bitCount(raw) % 2 == 1;
    }

    public static int labelCode(int raw) {
        return raw & LABEL_MASK;
    }

    /** Octal label as a zero-padded three digit string, e.g. "203". */
    public static String labelOctal(int raw) {
        return String.format("%03o", reverse8(raw & LABEL_MASK));
    }

    public static int sdi(int raw) {
        return (raw >>> 8) & SDI_MASK;
    }

    public static int data(int raw) {
        return (raw >>> 10) & DATA_MASK;
    }

    public static int ssm(int raw) {
        return (raw >>> 28) & SSM_MASK;
    }

    public static int parityBit(int raw) {
        return (raw >>> 31) & 1;
    }

    public static int bitAt(int raw, int arincBitNumber) {
        return (raw >>> (arincBitNumber - 1)) & 1;
    }

    /**
     * Parse a retained source representation. Import keeps the original text;
     * this only normalises to bits. Supported forms:
     *   0x.. / #.. hex (8 digits), 0b.. binary (32 digits), 0o.. octal,
     *   bare 32 0/1 digits, bare 8 hex digits.
     */
    public static int parseRawText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("raw word text is null");
        }
        String t = text.trim().replace("_", "");
        long value;
        try {
            if (t.startsWith("0x") || t.startsWith("0X") || t.startsWith("#")) {
                value = Long.parseLong(t.substring(t.startsWith("#") ? 1 : 2), 16);
            } else if (t.startsWith("0b") || t.startsWith("0B")) {
                value = Long.parseLong(t.substring(2), 2);
            } else if (t.startsWith("0o") || t.startsWith("0O")) {
                value = Long.parseLong(t.substring(2), 8);
            } else if (t.matches("[01]{32}")) {
                value = Long.parseLong(t, 2);
            } else if (t.matches("[0-9A-Fa-f]{8}")) {
                value = Long.parseLong(t, 16);
            } else {
                throw new IllegalArgumentException("unrecognised raw word representation: " + text);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cannot parse raw word: " + text, e);
        }
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("raw word is not 32 bits: " + text);
        }
        return (int) value;
    }

    public static String toHex(int raw) {
        return String.format("%08X", raw);
    }

    public static String toBinary(int raw) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 31; i >= 0; i--) {
            sb.append((raw >>> i) & 1);
        }
        return sb.toString();
    }

    /** Sign extend the 19-bit BNR data field (bit 29 is the BNR sign/MSB). */
    public static long signedBnr(int data19) {
        return ((long) data19 << (64 - 19)) >> (64 - 19);
    }

    public static double bnrEngineering(int data19, double lsbWeight) {
        return signedBnr(data19) * lsbWeight;
    }

    public static boolean isBnrNegative(int data19) {
        return ((data19 >>> 18) & 1) == 1;
    }

    /**
     * Decode BCD digits from the data field, least significant nibble first.
     * Returns digits (null entries for illegal 0xA..0xF nibbles) together
     * with 1-based positions of illegal digits. The unconvertible value is
     * never dropped: digits and diagnostics stay at the source position.
     */
    public static BcdResult decodeBcd(int data19, int digitCount) {
        List<Integer> digits = new ArrayList<>();
        List<Integer> illegalPositions = new ArrayList<>();
        for (int i = 0; i < digitCount; i++) {
            int nibble = (data19 >>> (i * 4)) & 0xF;
            if (nibble > 9) {
                digits.add(null);
                illegalPositions.add(i + 1);
            } else {
                digits.add(nibble);
            }
        }
        Double numeric = null;
        String rendered;
        if (illegalPositions.isEmpty()) {
            long n = 0;
            for (int i = digits.size() - 1; i >= 0; i--) {
                n = n * 10 + digits.get(i);
            }
            numeric = (double) n;
            rendered = String.valueOf(n);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = digits.size() - 1; i >= 0; i--) {
                sb.append(digits.get(i) == null ? '?' : (char) ('0' + digits.get(i)));
            }
            rendered = sb.toString();
        }
        return new BcdResult(digits, illegalPositions, rendered, numeric);
    }

    /** Decode a discrete word using a data-bit index (LSB of data = 0) to name map. */
    public static List<Map<String, Object>> decodeDiscrete(int data19, Map<Integer, String> bitNames) {
        List<Map<String, Object>> set = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            if (((data19 >>> i) & 1) == 1) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dataBit", i + 1);
                m.put("arincBit", i + 11);
                m.put("name", bitNames.getOrDefault(i, "D" + (i + 1)));
                set.add(m);
            }
        }
        return set;
    }

    /** SSM meaning per encoding family; the table comes from the rule version. */
    public static String ssmMeaning(String encoding, int ssm, Map<String, Map<Integer, String>> table) {
        Map<Integer, String> family = table.get(encoding);
        if (family == null) {
            family = table.get("BNR");
        }
        return family == null ? "?" : family.getOrDefault(ssm, "?");
    }

    /** A BNR/BCD/discrete word is functionally usable only when SSM == NO. */
    public static boolean ssmIsFunctional(String encoding, int ssm, Map<String, Map<Integer, String>> table) {
        return "NO".equals(ssmMeaning(encoding, ssm, table).split(" ")[0].trim());
    }

    public record BcdResult(List<Integer> digits, List<Integer> illegalPositions, String rendered, Double numeric) {
    }
}
