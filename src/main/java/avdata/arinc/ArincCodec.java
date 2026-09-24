package avdata.arinc;

import java.util.ArrayList;
import java.util.List;

/**
 * ARINC 429 word codec.
 *
 * <p>Conventions (documented in docs/arinc429.md):
 * <ul>
 *   <li>32-bit word, bits numbered 1..32 LSB-first (bit 1 is LSB, bit 32 parity MSB).</li>
 *   <li>Label: bits 1..8 transmitted LSB-first; the canonical 3-digit octal label is the
 *       bit-reversal of those 8 bits. Octal display never changes the stored bits.</li>
 *   <li>SDI: bits 9..10. Data: bits 11..29. SSM: bits 30..31. Parity: bit 32 (odd).</li>
 *   <li>"bit order reversal" (reversedWords) re-reverses the entire 32-bit transport word
 *       before field extraction, modelling a source that delivered the word bit-reversed.</li>
 * </ul>
 */
public final class ArincCodec {

    public static final int LABEL_BITS = 8;
    public static final int TOTAL_BITS = 32;

    private ArincCodec() {
    }

    /** Reverse the low {@code n} bits of {@code v}. */
    public static int reverseLow(int v, int n) {
        int out = 0;
        for (int i = 0; i < n; i++) {
            if (((v >>> i) & 1) == 1) {
                out |= 1 << (n - 1 - i);
            }
        }
        return out;
    }

    /** Reverse all 32 bits of a transport word. */
    public static int reverse32(int word) {
        return Integer.reverse(word);
    }

    public static int labelField(int word) {
        return word & 0xFF;
    }

    /** Octal label number (bit-reversed label field), 0..377. */
    public static int octalLabel(int word) {
        return reverseLow(word & 0xFF, 8);
    }

    /** Three-digit octal display, e.g. "203". Display only; bits are untouched. */
    public static String octalLabelText(int word) {
        return String.format("%03o", octalLabel(word));
    }

    public static int sdi(int word) {
        return (word >>> 8) & 0x3;
    }

    public static int ssm(int word) {
        return (word >>> 29) & 0x3;
    }

    public static int dataBits(int word) {
        return (word >>> 10) & 0x7FFFF; // bits 11..29 -> 19 bits
    }

    public static int parityBit(int word) {
        return (word >>> 31) & 1;
    }

    /** ARINC 429 odd parity: total number of ONE bits (incl. parity) must be odd. */
    public static boolean oddParityValid(int word) {
        return Integer.bitCount(word) % 2 == 1;
    }

    /**
     * Extract a contiguous bit range (bit numbers 1-based, LSB-first), returned as an int.
     * {@code fromBit} is the lower-numbered (LSB) bit.
     */
    public static int extract(int word, int fromBit, int toBit) {
        int width = toBit - fromBit + 1;
        int mask = width >= 32 ? -1 : ((1 << width) - 1);
        return (word >>> (fromBit - 1)) & mask;
    }

    /** Every bit as a 32-char string, index 0 == bit 1. */
    public static String bitString(int word) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append((word >>> i) & 1);
        }
        return sb.toString();
    }

    /** Parse an unsigned 32-bit hex literal such as "0x000CE4C1". */
    public static int parseRawHex(String raw) {
        String s = raw.trim();
        if (s.toLowerCase().startsWith("0x")) {
            s = s.substring(2);
        }
        if (s.isEmpty() || s.length() > 8) {
            throw new IllegalArgumentException("invalid raw word: " + raw);
        }
        long v = Long.parseLong(s, 16);
        if (v > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("raw word exceeds 32 bits: " + raw);
        }
        return (int) v;
    }

    // ------------------------------------------------------------------
    // Engineering-value interpretation (dictionary-driven)
    // ------------------------------------------------------------------

    public enum Encoding { BNR, BCD, DISCRETE }

    public static final class LabelDef {
        public final String label;          // octal display text, e.g. "203"
        public final int sdi;               // -1 = any SDI
        public final String name;
        public final String unit;
        public final String coordinateBase; // coordinate/reference basis, may be ""
        public final Encoding encoding;
        public final int msb;               // BNR data MSB bit number (1-based)
        public final int lsb;               // BNR data LSB bit number
        public final double resolution;     // BNR: engineering units per LSB (signed range +/-(2^19)*res)
        public final List<String> discreteNames; // DISCRETE: name per bit (LSB-first within data)

        public LabelDef(String label, int sdi, String name, String unit, String coordinateBase,
                        Encoding encoding, int msb, int lsb, double resolution,
                        List<String> discreteNames) {
            this.label = label;
            this.sdi = sdi;
            this.name = name;
            this.unit = unit == null ? "" : unit;
            this.coordinateBase = coordinateBase == null ? "" : coordinateBase;
            this.encoding = encoding;
            this.msb = msb;
            this.lsb = lsb;
            this.resolution = resolution;
            this.discreteNames = discreteNames == null ? List.of() : List.copyOf(discreteNames);
        }

        public boolean matches(String octalText, int wordSdi) {
            return label.equals(octalText) && (sdi < 0 || sdi == wordSdi);
        }
    }

    /** Result of interpreting a single word's data field under one dictionary definition. */
    public static final class Interpretation {
        public final boolean convertible;
        public final Double value;          // null when not convertible / invalid
        public final String unit;
        public final String diagnostic;     // non-empty when value kept in place with a diagnosis
        public final List<String> discreteStates; // DISCRETE labels
        public final String rawRepresentation;    // original textual representation preserved

        private Interpretation(boolean convertible, Double value, String unit, String diagnostic,
                               List<String> discreteStates, String rawRepresentation) {
            this.convertible = convertible;
            this.value = value;
            this.unit = unit;
            this.diagnostic = diagnostic;
            this.discreteStates = discreteStates;
            this.rawRepresentation = rawRepresentation;
        }

        public static Interpretation numeric(double v, String unit, String raw) {
            return new Interpretation(true, v, unit, "", List.of(), raw);
        }

        public static Interpretation discrete(List<String> states, String unit, String raw) {
            return new Interpretation(true, null, unit, "", states, raw);
        }

        public static Interpretation unconvertible(String diagnostic, String unit, String raw) {
            return new Interpretation(false, null, unit, diagnostic, List.of(), raw);
        }
    }

    /**
     * Decode a word's data field under {@code def}. SSM handling is encoded by the dictionary
     * rule-set version (see DictionaryDef.ssmMode) but the common ARINC semantics are applied
     * here:
     *
     * <p>BNR: SSM 00=normal, 01=test, 10=no computed data, 11=functional test/failure warning.
     * <br>BCD: SSM 00=normal(+), 10=normal(-) [sign = bit30], 01=no computed data,
     * 11=functional test/failure warning.
     *
     * <p>When SSM is not "normal", the value is kept in place with a diagnostic rather than
     * dropped or fudged.
     */
    public static Interpretation interpret(int word, LabelDef def, String rawHex,
                                           SsmRuleSet ssmRules) {
        int ssm = ssm(word);
        String ssmDiag = ssmRules.diagnostic(def.encoding, ssm);
        switch (def.encoding) {
            case BNR: {
                if (ssmDiag != null) {
                    return Interpretation.unconvertible("SSM " + ssmText(ssm) + ": " + ssmDiag,
                            def.unit, rawHex);
                }
                int width = def.msb - def.lsb + 1;
                int raw = extract(word, def.lsb, def.msb);
                long signed = signExtend(raw, width);
                return Interpretation.numeric(signed * def.resolution, unitWithBase(def), rawHex);
            }
            case BCD: {
                if (ssmRules.isFailure(def.encoding, ssm) || ssmRules.isNoData(def.encoding, ssm)) {
                    return Interpretation.unconvertible("SSM " + ssmText(ssm) + ": " + ssmDiag,
                            def.unit, rawHex);
                }
                // 5 nibbles from bit 11 upward (LSB-first digit groups), matching case pack.
                List<Character> digits = new ArrayList<>();
                List<Integer> invalid = new ArrayList<>();
                for (int k = 0; k < 5; k++) {
                    int nib = extract(word, 11 + 4 * k, 14 + 4 * k);
                    if (nib > 9) {
                        invalid.add(nib);
                    }
                    digits.add(nib > 9 ? '?' : Character.forDigit(nib, 10));
                }
                if (!invalid.isEmpty()) {
                    return Interpretation.unconvertible(
                            "illegal BCD digit(s) 0x" + Integer.toHexString(invalid.get(0))
                                    + " in data field; raw groups retained",
                            def.unit, rawHex);
                }
                long mag = 0;
                for (int k = digits.size() - 1; k >= 0; k--) {
                    mag = mag * 10 + (digits.get(k) - '0');
                }
                double v = mag;
                // BCD sign comes from SSM bit30 in the signed-BCD convention (rule-set versioned).
                if (ssmRules.bcdSignFromSsm() && ((ssm & 0b10) == 0b10)) {
                    v = -v;
                }
                return Interpretation.numeric(v, unitWithBase(def), rawHex);
            }
            case DISCRETE: {
                if (ssmDiag != null) {
                    return Interpretation.unconvertible("SSM " + ssmText(ssm) + ": " + ssmDiag,
                            def.unit, rawHex);
                }
                int width = Math.min(def.discreteNames.size(), 19);
                List<String> states = new ArrayList<>();
                for (int i = 0; i < width; i++) {
                    int bit = (word >>> (10 + i)) & 1;
                    states.add(def.discreteNames.get(i) + "=" + bit);
                }
                return Interpretation.discrete(states, unitWithBase(def), rawHex);
            }
            default:
                throw new IllegalStateException("unknown encoding");
        }
    }

    private static String unitWithBase(LabelDef def) {
        if (def.coordinateBase.isEmpty()) {
            return def.unit;
        }
        return def.unit + " (" + def.coordinateBase + ")";
    }

    public static String ssmText(int ssm) {
        return String.format("%02d", Integer.parseInt(Integer.toBinaryString(ssm)));
    }

    static long signExtend(int raw, int width) {
        long v = raw & 0xFFFFFFFFL;
        long sign = 1L << (width - 1);
        if ((v & sign) != 0) {
            v |= ~((1L << width) - 1);
        }
        return v;
    }

    /** Versioned SSM semantics. Two rule sets are shipped to prove interpretation is
     *  dictionary-version dependent rather than hard-coded. */
    public enum SsmRuleSet {
        /** Classic ARINC 429 annex semantics used by the initial dictionary. */
        CLASSIC {
            @Override
            public String diagnostic(Encoding e, int ssm) {
                switch (e) {
                    case BNR:
                        switch (ssm) {
                            case 0b01: return "test";
                            case 0b10: return "no computed data";
                            case 0b11: return "functional test / failure warning";
                            default: return null;
                        }
                    case BCD:
                    case DISCRETE:
                        switch (ssm) {
                            case 0b01: return "no computed data";
                            case 0b11: return "functional test / failure warning";
                            default: return null;
                        }
                    default: return null;
                }
            }

            @Override
            public boolean bcdSignFromSsm() {
                return true;
            }
        },
        /** Revised rule set: BCD negative sign is encoded in the spare high data bit
         *  (bit 29) instead of SSM bit30, and SSM 10 on BNR means "caution/hold" but the
         *  last good value is still presented as a candidate. */
        REVISED {
            @Override
            public String diagnostic(Encoding e, int ssm) {
                switch (e) {
                    case BNR:
                        if (ssm == 0b01) {
                            return "test";
                        }
                        if (ssm == 0b11) {
                            return "functional test / failure warning";
                        }
                        // 10: hold candidate - not an invalidity in the revised rule set
                        return null;
                    case BCD:
                    case DISCRETE:
                        if (ssm == 0b11) {
                            return "functional test / failure warning";
                        }
                        return null;
                    default:
                        return null;
                }
            }

            @Override
            public boolean bcdSignFromSsm() {
                return false;
            }
        };

        public abstract String diagnostic(Encoding e, int ssm);

        public abstract boolean bcdSignFromSsm();

        public boolean isFailure(Encoding e, int ssm) {
            return ssm == 0b11;
        }

        public boolean isNoData(Encoding e, int ssm) {
            String d = diagnostic(e, ssm);
            return d != null && d.contains("no computed data");
        }
    }
}
