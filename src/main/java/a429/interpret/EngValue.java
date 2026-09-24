package a429.interpret;

/**
 * Result of engineering interpretation.
 *
 * @param valid     false when the bits cannot be converted (illegal BCD digit, SSM non-normal)
 * @param value     numeric value when valid (Boolean becomes 0/1), else NaN
 * @param unit      engineering unit or coordinate basis
 * @param diagnosis machine-readable diagnostic code(s), empty when valid
 * @param dictVersion dictionary revision that produced the resolution
 */
public record EngValue(boolean valid, double value, String unit, String display,
                       String diagnosis, String dictVersion, String dictEntryUid) {

    public static EngValue invalid(String diagnosis, String dictVersion, String dictEntryUid) {
        return new EngValue(false, Double.NaN, "", "", diagnosis, dictVersion, dictEntryUid);
    }
}
