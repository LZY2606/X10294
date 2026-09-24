package avionics;

import java.util.ArrayList;
import java.util.List;

/**
 * Derived-layer decoder. Raw words are never rewritten; decoding produces
 * engineering-value candidates with units, basis and diagnostics. Values that
 * cannot be converted stay in place with a diagnostic instead of being dropped.
 */
public final class Decoder {

    private Decoder() {}

    /** SSM codes for BNR/BCD data (ARINC 429). */
    public static String ssmMeaning(int ssm) {
        return switch (ssm) {
            case 0 -> "FAILURE_WARNING";
            case 1 -> "NO_COMPUTED_DATA";
            case 2 -> "FUNCTIONAL_TEST";
            case 3 -> "NORMAL";
            default -> "UNKNOWN";
        };
    }

    public static boolean ssmValidForUse(int ssm) {
        return ssm == 3;
    }

    public record Result(
            boolean convertible,
            Double value,          // engineering value when convertible
            String unit,
            String basis,
            List<String> diagnostics
    ) {}

    public static Result decode(Dictionary.Entry entry, long dataField, int ssm) {
        List<String> diag = new ArrayList<>();
        if (!ssmValidForUse(ssm)) {
            diag.add("SSM=" + ssm + " (" + ssmMeaning(ssm) + "): word not usable for aggregation");
        }
        switch (entry.encoding()) {
            case "BNR" -> {
                long signed = Arinc.signExtend(dataField, entry.dataBits());
                double value = signed * entry.scale();
                return new Result(true, value, entry.unit(), entry.basis(), diag);
            }
            case "BCD" -> {
                // 5 BCD nibbles across the 19-bit data field (bits 11-29; top nibble partial).
                StringBuilder digits = new StringBuilder();
                boolean illegal = false;
                for (int i = 4; i >= 0; i--) {
                    int nibble = (int) ((dataField >>> (i * 4)) & 0xF);
                    if (nibble > 9) {
                        illegal = true;
                        diag.add("BCD illegal digit 0x" + Integer.toHexString(nibble).toUpperCase()
                                + " at nibble " + i + ": value left unconverted in place");
                    }
                    digits.append(nibble <= 9 ? (char) ('0' + nibble) : '?');
                }
                if (illegal) {
                    return new Result(false, null, entry.unit(), entry.basis(), diag);
                }
                double value = Long.parseLong(digits.toString()) * entry.scale();
                return new Result(true, value, entry.unit(), entry.basis(), diag);
            }
            case "DISCRETE" -> {
                return new Result(true, (double) dataField, entry.unit(), entry.basis(), diag);
            }
            default -> {
                diag.add("unknown encoding " + entry.encoding());
                return new Result(false, null, entry.unit(), entry.basis(), diag);
            }
        }
    }
}
