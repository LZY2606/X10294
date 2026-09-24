package a429;

import com.fasterxml.jackson.databind.JsonNode;

/** Engineering-value interpretation. Encoding rules come from the label dictionary
 *  version pinned on the branch; unconvertible values stay in place with a diagnostic. */
public final class Interpreter {

    public record Result(String status, Double value, String unit, String text, String diagnostic) {
        static Result ok(Double v, String unit, String text) { return new Result("OK", v, unit, text, null); }
        static Result diag(String status, String text, String diagnostic) { return new Result(status, null, null, text, diagnostic); }
    }

    /** dataField: 19-bit data field (bits 11-29). ssm: bits 30-31. */
    public static Result interpret(JsonNode dictEntry, long dataField, int ssm) {
        if (dictEntry == null) {
            return Result.diag("NO_DICT_HIT", null, "label not present in pinned dictionary version");
        }
        String encoding = dictEntry.get("encoding").asText();
        String unit = dictEntry.has("unit") ? dictEntry.get("unit").asText() : null;
        Result r;
        switch (encoding) {
            case "BNR" -> {
                long v = (dataField & 0x40000) != 0 ? dataField - 0x80000 : dataField; // 19-bit two's complement
                double res = dictEntry.get("resolution").asDouble();
                double eng = v * res;
                r = Result.ok(eng, unit, trim(eng) + " " + unit);
            }
            case "BCD" -> {
                StringBuilder digits = new StringBuilder();
                boolean illegal = false;
                for (int n = 3; n >= 0; n--) {
                    int nib = (int) ((dataField >> (n * 4)) & 0xF);
                    if (nib > 9) { illegal = true; digits.append('?'); }
                    else digits.append((char) ('0' + nib));
                }
                if (illegal) {
                    r = Result.diag("ILLEGAL_BCD", "BCD " + digits,
                            "BCD nibble outside 0-9; raw data field 0x" + Long.toHexString(dataField) + " kept in place");
                } else {
                    r = Result.ok(null, unit, "BCD " + digits);
                }
            }
            case "DISCRETE" -> {
                StringBuilder bits = new StringBuilder();
                for (int b = 18; b >= 0; b--) bits.append((dataField >> b) & 1);
                r = Result.ok(null, unit, "discrete " + bits);
            }
            default -> r = Result.diag("UNKNOWN_ENCODING", null, "encoding " + encoding + " not supported");
        }
        if (r.status().equals("OK") && ssm != 3) {
            String ssmName = switch (ssm) { case 0 -> "FAILURE_WARNING"; case 1 -> "NO_COMPUTED_DATA"; case 2 -> "FUNCTIONAL_TEST"; default -> "?"; };
            return new Result("SSM_INVALID", r.value(), r.unit(), r.text(),
                    "SSM=" + ssm + " (" + ssmName + ") is not normal operation; value kept but excluded from aggregates");
        }
        return r;
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return Double.toString(v);
    }
}
