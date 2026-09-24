package avi.a429.core;

/**
 * Result of interpreting one raw word under one dictionary entry.
 * Unconvertible values keep {@code engValue == null} and carry a diagnostic;
 * the raw word stays in place.
 */
public record DecodeResult(
        Status status,
        Double engValue,
        String unit,
        String basis,
        String diagnostic) {

    public enum Status {
        OK,
        EXCLUDED_PARITY,   // bad parity: never enters aggregated values
        INVALID_BCD,       // BCD nibble outside 0..9
        SSM_INVALID,       // SSM code maps to an invalid state
        NO_DICT_ENTRY,     // label not present in this dictionary version
        UNCONVERTIBLE      // unit/scale missing; value left in place with diagnostic
    }

    public static DecodeResult ok(double v, String unit, String basis) {
        return new DecodeResult(Status.OK, v, unit, basis, null);
    }

    public static DecodeResult of(Status s, String unit, String basis, String diag) {
        return new DecodeResult(s, null, unit, basis, diag);
    }
}
