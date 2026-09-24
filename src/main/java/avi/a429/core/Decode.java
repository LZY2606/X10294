package avi.a429.core;

/**
 * Interprets the data field of an ARINC 429 word under a dictionary entry.
 * All normalization happens here in the derived layer; raw words keep their
 * original representation in the store.
 */
public final class Decode {

    private Decode() {}

    public static DecodeResult decode(int word, DictEntry entry, boolean parityOk) {
        if (!parityOk) {
            return DecodeResult.of(DecodeResult.Status.EXCLUDED_PARITY,
                    entry != null ? entry.unit() : null,
                    entry != null ? entry.basis() : null,
                    "odd parity check failed; word excluded from aggregated values");
        }
        if (entry == null) {
            return DecodeResult.of(DecodeResult.Status.NO_DICT_ENTRY, null, null,
                    "label " + Arinc429.labelOctal(word) + " not in dictionary version");
        }
        String ssmMeaning = entry.ssmMeaning(Arinc429.ssm(word));
        if ("invalid".equals(ssmMeaning)) {
            return DecodeResult.of(DecodeResult.Status.SSM_INVALID, entry.unit(), entry.basis(),
                    "SSM=" + Arinc429.ssm(word) + " is an invalid state for " + entry.encoding());
        }
        return switch (entry.encoding()) {
            case "BNR" -> bnr(word, entry, ssmMeaning);
            case "BCD" -> bcd(word, entry, ssmMeaning);
            case "DISC" -> disc(word, entry);
            default -> DecodeResult.of(DecodeResult.Status.UNCONVERTIBLE, entry.unit(), entry.basis(),
                    "unknown encoding " + entry.encoding());
        };
    }

    /** BNR: 19-bit two's complement data field (bits 11..29), scaled by dict entry. */
    static DecodeResult bnr(int word, DictEntry entry, String ssmMeaning) {
        int raw = Arinc429.dataField(word);
        if ((raw & 0x40000) != 0) {
            raw -= 0x80000; // two's complement sign (bit 29)
        }
        double value = raw * entry.scale() + entry.offset();
        String diag = "ncd".equals(ssmMeaning) || "test".equals(ssmMeaning)
                ? "SSM=" + ssmMeaning + "; value kept as candidate, not for aggregation"
                : null;
        if (diag != null) {
            return new DecodeResult(DecodeResult.Status.SSM_INVALID, value, entry.unit(), entry.basis(), diag);
        }
        return DecodeResult.ok(value, entry.unit(), entry.basis());
    }

    /** BCD: five nibbles in bits 11..29; any nibble > 9 is an illegal digit. */
    static DecodeResult bcd(int word, DictEntry entry, String ssmMeaning) {
        int raw = Arinc429.dataField(word);
        int[] digits = new int[5];
        for (int i = 0; i < 5; i++) {
            int nibble = (raw >>> (i * 4)) & 0xF;
            if (nibble > 9) {
                return DecodeResult.of(DecodeResult.Status.INVALID_BCD, entry.unit(), entry.basis(),
                        "illegal BCD digit 0x" + Integer.toHexString(nibble).toUpperCase()
                                + " at nibble " + i + "; raw value left in place");
            }
            digits[i] = nibble;
        }
        int intPart = digits[4] * 10000 + digits[3] * 1000 + digits[2] * 100 + digits[1] * 10 + digits[0];
        double value = intPart * entry.scale() + entry.offset();
        return DecodeResult.ok(value, entry.unit(), entry.basis());
    }

    /** Discrete: value is the raw data field; named bits come from the dictionary. */
    static DecodeResult disc(int word, DictEntry entry) {
        int raw = Arinc429.dataField(word);
        return DecodeResult.ok(raw, entry.unit(), entry.basis());
    }
}
