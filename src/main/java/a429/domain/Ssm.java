package a429.domain;

import java.util.Locale;

/**
 * SSM（bit 30..31）解释。BNR/BCD 与 discrete 的编码不同：
 *   BNR/BCD:     00=NO_COMPUTED_DATA, 01=FUNCTIONAL_TEST, 10=NORMAL_OPERATION, 11=WARNING_FAILURE
 *   DISCRETE:    bit30 为功能测试位，bit31 为 NCD/告警位；00=NORMAL_OPERATION 才正常
 */
public final class Ssm {

    public static final int NO_COMPUTED_DATA = 0b00;
    public static final int FUNCTIONAL_TEST = 0b01;
    public static final int NORMAL_OPERATION = 0b10;
    public static final int WARNING_FAILURE = 0b11;

    private Ssm() {
    }

    public static String name(int code, String encoding) {
        return switch (code) {
            case NO_COMPUTED_DATA -> "NO_COMPUTED_DATA";
            case FUNCTIONAL_TEST -> "FUNCTIONAL_TEST";
            case NORMAL_OPERATION -> "NORMAL_OPERATION";
            case WARNING_FAILURE -> "WARNING_FAILURE";
            default -> String.format(Locale.ROOT, "UNKNOWN(%02o)", code);
        };
    }

    /** 是否可作为有效工程值参与聚合。discrete 仅 00(NORMAL_OPERATION=0b00) 有效。 */
    public static boolean operationallyValid(int code, String encoding) {
        if ("DISCRETE".equalsIgnoreCase(encoding)) {
            return code == 0b00;
        }
        return code == NORMAL_OPERATION;
    }
}
