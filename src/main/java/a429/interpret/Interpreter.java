package a429.interpret;

import a429.codec.FieldValues;
import a429.codec.SsmState;
import a429.codec.Words;
import a429.model.Dictionary;
import a429.model.Encoding;
import a429.model.LabelDef;

/**
 * Resolves a word to an engineering value using a dictionary revision. BNR/BCD/discrete choice is
 * entirely a dictionary decision, so two versions may explain identical bits differently.
 * Bad SSM state prevents aggregation but is retained as a diagnosis, not dropped.
 */
public final class Interpreter {
    private Interpreter() {}

    public static EngValue interpret(int word, Dictionary dictionary) {
        String octal = Words.labelOctal(word);
        LabelDef def = dictionary.get(octal);
        if (def == null) {
            return EngValue.invalid("DICT_MISS:" + octal, dictionary.version(), null);
        }
        String entryUid = dictionary.dictId() + ":" + def.labelOctal();
        SsmState ssm = SsmState.fromBits(Words.ssm(word));
        int data = Words.data(word);
        if (ssm != SsmState.NORMAL) {
            return EngValue.invalid("SSM_" + ssm.name(), dictionary.version(), entryUid);
        }
        if (def.encoding() == Encoding.BNR) {
            double value = FieldValues.bnr(data, def.bnrBits(), def.fullScale());
            String display = format(value) + " " + def.unit();
            return new EngValue(true, value, def.unit(), display, "",
                    dictionary.version(), entryUid);
        }
        if (def.encoding() == Encoding.BCD) {
            int[] digits = FieldValues.bcdDigits(data);
            if (!FieldValues.bcdDigitsValid(digits)) {
                StringBuilder illegal = new StringBuilder();
                for (int i = 0; i < digits.length; i++) {
                    if (digits[i] > 9) {
                        if (illegal.length() > 0) {
                            illegal.append(",");
                        }
                        illegal.append("d").append(i + 1).append("=")
                                .append(String.format("%X", digits[i]));
                    }
                }
                return EngValue.invalid("BCD_ILLEGAL_DIGIT[" + illegal + "]",
                        dictionary.version(), entryUid);
            }
            int magnitude = FieldValues.bcdMagnitude(digits);
            String display = magnitude + " " + def.unit();
            return new EngValue(true, magnitude, def.unit(), display, "",
                    dictionary.version(), entryUid);
        }
        StringBuilder bits = new StringBuilder();
        for (int i = 19; i >= 1; i--) {
            bits.append(FieldValues.discreteBit(data, i));
        }
        String display = "0b" + bits;
        return new EngValue(true, Integer.parseInt(bits.toString(), 2), "bits", display, "",
                dictionary.version(), entryUid);
    }

    private static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.6f", value);
    }
}
