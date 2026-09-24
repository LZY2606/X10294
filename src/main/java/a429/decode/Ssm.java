package a429.decode;

import a429.domain.EncodingType;

/**
 * Sign/Status Matrix interpretation. BNR and BCD/discrete use different tables;
 * SSM=11 is universally invalid and must not enter aggregate values.
 */
public final class Ssm {

    private Ssm() {
    }

    public static boolean invalid(EncodingType encoding, int ssm) {
        if (ssm == 0b11) {
            return true;
        }
        if (encoding == EncodingType.BCD || encoding == EncodingType.DISCRETE) {
            return ssm == 0b01 || ssm == 0b10;
        }
        return false;
    }

    public static String meaning(EncodingType encoding, int ssm) {
        if (encoding == EncodingType.BNR) {
            return switch (ssm) {
                case 0b00 -> "failure warning";
                case 0b01 -> "functional test";
                case 0b10 -> "no computed data";
                case 0b11 -> "functional test";
                default -> "unknown";
            };
        }
        return switch (ssm) {
            case 0b00 -> "normal operation";
            case 0b01 -> "functional test";
            case 0b10 -> "no computed data";
            case 0b11 -> "failure warning";
            default -> "unknown";
        };
    }
}
