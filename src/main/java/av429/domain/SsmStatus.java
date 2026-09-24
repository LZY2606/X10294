package av429.domain;

/**
 * SSM interpretation depends on the data type of the label, which in turn is
 * decided by the dictionary version that decodes the word.
 */
public enum SsmStatus {
    NOMINAL("nominal", "Nominal / Functional Test off"),
    FUNCTIONAL_TEST("functional_test", "Functional test"),
    NOT_COMPUTED("not_computed", "No computed data / NCD"),
    INVALID("invalid", "Failure warning / invalid data"),
    UNKNOWN("unknown", "Not decodable");

    private final String code;
    private final String description;

    SsmStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static SsmStatus decode(int ssm, String dataType) {
        boolean bnrLike = dataType == null || "BNR".equalsIgnoreCase(dataType)
                || "DISCRETE".equalsIgnoreCase(dataType);
        if (bnrLike) {
            switch (ssm) {
                case 0b00: return NOMINAL;
                case 0b01: return FUNCTIONAL_TEST;
                case 0b10: return NOT_COMPUTED;
                case 0b11: return INVALID;
                default: return UNKNOWN;
            }
        }
        // BCD / AIS data
        switch (ssm) {
            case 0b00: return NOMINAL;
            case 0b01: return NOMINAL; // BCD bit pattern 01 is also normal data
            case 0b10: return FUNCTIONAL_TEST;
            case 0b11: return INVALID;
            default: return UNKNOWN;
        }
    }

    public boolean blocksAggregate() {
        return this == INVALID || this == NOT_COMPUTED || this == UNKNOWN;
    }
}
