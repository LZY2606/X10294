package avdata.a429.decode;

/** Sign/Status Matrix decoding; meaning depends on the data encoding. */
public final class Ssm {
    private Ssm() {}

    /** @param encoding BNR | BCD | DISCRETE */
    public static String meaning(String encoding, String ssm) {
        return switch (encoding) {
            case "BNR", "DISCRETE" -> switch (ssm) {
                case "00" -> "FAILURE_WARNING";
                case "01" -> "NO_COMPUTED_DATA";
                case "10" -> "FUNCTIONAL_TEST";
                case "11" -> "NORMAL_OPERATION";
                default -> "UNKNOWN";
            };
            case "BCD" -> switch (ssm) {
                case "00" -> "PLUS_NORTH_EAST_RIGHT_TO";
                case "01" -> "NO_COMPUTED_DATA";
                case "10" -> "FUNCTIONAL_TEST";
                case "11" -> "MINUS_SOUTH_WEST_LEFT_FROM";
                default -> "UNKNOWN";
            };
            default -> "UNKNOWN";
        };
    }

    /** SSM states that make an engineering value invalid for aggregation. */
    public static boolean isInvalidState(String encoding, String ssm) {
        return switch (ssm) {
            case "01", "10" -> true;            // NCD / functional test
            case "00" -> "BNR".equals(encoding) || "DISCRETE".equals(encoding);
            default -> false;
        };
    }
}
