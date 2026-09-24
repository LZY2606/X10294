package a429.domain;

import java.util.List;
import java.util.Map;

/**
 * One label definition inside a dictionary version.
 * {@code kind} is one of BNR, BCD, DISCRETE.
 * A missing SDI match ({@code sdiMatch == null}) accepts every SDI value.
 */
public record DictEntry(
        String canonicalLabel,
        String name,
        String kind,
        String unit,
        String datum,
        Double resolution,
        Integer bnrSignArinc,
        Integer bcdWidth,
        List<BcdDigit> bcdDigits,
        List<DiscreteBit> discreteBits,
        Integer sdiMatch,
        String ssmScheme,
        Map<String, Object> extra
) {
    /** One BCD digit: ARINC bit span plus its decimal weight. */
    public record BcdDigit(int msb, int lsb, int weight) {
    }

    /** One discrete status bit with stable bit-code and dict-supplied meaning. */
    public record DiscreteBit(int arincBit, String code, String meaningWhenOne) {
    }

    public static DictEntry bnr(String label, String name, String unit, String datum,
                                double resolution, Integer signArinc,
                                Integer sdiMatch, String ssmScheme) {
        return new DictEntry(label, name, "BNR", unit, datum, resolution,
                signArinc, null, null, null, sdiMatch, ssmScheme, Map.of());
    }

    public static DictEntry bcd(String label, String name, String unit, String datum,
                                int width, List<BcdDigit> digits,
                                Integer sdiMatch, String ssmScheme) {
        return new DictEntry(label, name, "BCD", unit, datum, null,
                null, width, digits, null, sdiMatch, ssmScheme, Map.of());
    }

    public static DictEntry discrete(String label, String name, List<DiscreteBit> bits,
                                     Integer sdiMatch, String ssmScheme) {
        return new DictEntry(label, name, "DISCRETE", "state", "bitfield", null,
                null, null, null, bits, sdiMatch, ssmScheme, Map.of());
    }
}
