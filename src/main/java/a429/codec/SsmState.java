package a429.codec;

/**
 * ARINC 429 SSM (Sign/Status Matrix) interpretation for a data word.
 * Only NORMAL operation yields an aggregate-able engineering value.
 */
public enum SsmState {
    FAILURE_WARNING(0b00),
    NO_COMPUTED_DATA(0b01),
    FUNCTIONAL_TEST(0b10),
    NORMAL(0b11);

    private final int code;

    SsmState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean aggregates() {
        return this == NORMAL;
    }

    public static SsmState fromBits(int ssmBits) {
        for (SsmState state : values()) {
            if (state.code == ssmBits) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown SSM code " + ssmBits);
    }
}
