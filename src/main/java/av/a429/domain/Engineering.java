package av.a429.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Engineering-value interpretation. Raw representation is always kept;
 * normalisation happens only in this derived layer. Values that cannot be
 * converted stay in place with diagnostics instead of being dropped.
 */
public final class Engineering {

    public record Value(String wordId, String rawHex, String labelOctal,
                        Double normalized, String unit, String coordBasis,
                        String dictVersion, String dictEntryId,
                        List<String> diagnostics, boolean aggregable) {

        public String display() {
            if (normalized != null) {
                return String.format("%.3f %s", normalized, unit == null ? "" : unit).trim();
            }
            return "RAW 0x" + rawHex + " (unconverted)";
        }
    }

    private Engineering() {}

    public static Value interpret(String wordId, WordDecoder.Decoded dec, LabelDictionary dict) {
        List<String> diag = new ArrayList<>();
        if (!dec.parityOk()) {
            diag.add("PARITY_ERROR: odd parity check failed; word excluded from aggregation");
        }
        if (!WordDecoder.ssmValidForData(dec.ssm())) {
            diag.add("SSM_INVALID_STATUS: SSM=" + dec.ssm() + " (" + WordDecoder.ssmName(dec.ssm()) + ")");
        }

        var entryOpt = dict.lookup(dec.labelOctal());
        if (entryOpt.isEmpty()) {
            diag.add("DICT_MISS: label " + dec.labelOctal() + " (octal) not in dictionary " + dict.version());
            return new Value(wordId, dec.rawHex(), dec.labelOctal(), null, null, null,
                    dict.version(), null, diag, false);
        }
        var entry = entryOpt.get();
        Double normalized = null;
        switch (entry.encoding()) {
            case BNR -> normalized = WordDecoder.decodeBnr(dec.dataField(), entry.scale());
            case BCD -> {
                var bcd = WordDecoder.decodeBcd(dec.dataField(), entry.scale());
                if (bcd.illegal()) {
                    diag.add("BCD_ILLEGAL_DIGIT: nibble > 9 in digits " + bcd.digits());
                }
                normalized = bcd.value();
            }
            case DISCRETE -> normalized = null; // discrete bits stay raw by design
        }
        boolean aggregable = dec.parityOk() && WordDecoder.ssmValidForData(dec.ssm()) && normalized != null;
        return new Value(wordId, dec.rawHex(), dec.labelOctal(), normalized,
                entry.unit(), entry.coordBasis(), dict.version(), entry.entryId(), diag, aggregable);
    }
}
