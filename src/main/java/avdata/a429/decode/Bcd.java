package avdata.a429.decode;

import java.util.ArrayList;
import java.util.List;

/** Binary-coded decimal helpers on nibbles packed from the given data field. */
public final class Bcd {
    private Bcd() {}

    public record Result(Double value, String rawText, List<Interpretation.Diagnostic> diagnostics,
                         List<Integer> nibbles) {}

    /** Decode up to {@code count} BCD nibbles, least-significant display digit first. */
    public static Result decode(int dataField, int count) {
        List<Interpretation.Diagnostic> diags = new ArrayList<>();
        List<Integer> nibbles = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        boolean illegal = false;
        long value = 0;
        for (int i = 0; i < count; i++) {
            int nibble = (dataField >>> (4 * i)) & 0xF;
            nibbles.add(nibble);
            if (nibble > 9) {
                illegal = true;
                diags.add(new Interpretation.Diagnostic(
                        "BCD_ILLEGAL_DIGIT",
                        "nibble " + i + " = " + Integer.toHexString(nibble).toUpperCase()
                                + " is not a legal BCD digit"));
            } else {
                value = value * 10 + nibble;
            }
            raw.insert(0, nibble > 9 ? Integer.toHexString(nibble).toUpperCase() : Integer.toString(nibble));
        }
        return new Result(illegal ? null : (double) value, raw.toString(), diags, nibbles);
    }
}
