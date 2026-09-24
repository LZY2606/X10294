package a429.core;

/**
 * Dictionary-versioned interpretation of extracted bit fields.
 *
 * Rules enforced here:
 *  - bad-parity words never enter aggregated values;
 *  - BNR/BCD/discrete meaning comes only from the pinned dictionary version;
 *  - unconvertible values stay in place with a diagnostic, they are never dropped.
 */
public final class Interpreter {

    public static final String OK = "OK";
    public static final String PARITY_BAD = "PARITY_BAD";
    public static final String SSM_INVALID = "SSM_INVALID";
    public static final String BCD_ILLEGAL = "BCD_ILLEGAL";
    public static final String NO_DICT_ENTRY = "NO_DICT_ENTRY";

    private Interpreter() {}

    /** Where the displayed value's meaning came from. */
    public record DictHit(String dictionaryVersion, String labelOctal, String mnemonic, boolean found) {}

    public record ValueResult(
            String status,
            Double engValue,
            String unit,
            String diagnostic,
            DictHit hit,
            boolean entersAggregate) {}

    public static ValueResult interpret(Arinc429.Fields f, Dictionary dict) {
        if (!f.parityOk()) {
            return new ValueResult(PARITY_BAD, null, "", "odd parity check failed; word excluded from aggregates",
                    new DictHit(dict.version(), f.labelOctal(), "", false), false);
        }
        Dictionary.Entry entry = dict.lookup(f.labelOctal());
        if (entry == null) {
            return new ValueResult(NO_DICT_ENTRY, null, "",
                    "label 0o" + f.labelOctal() + " not present in dictionary " + dict.version()
                            + "; raw data field retained",
                    new DictHit(dict.version(), f.labelOctal(), "", false), false);
        }
        DictHit hit = new DictHit(dict.version(), f.labelOctal(), entry.mnemonic(), true);
        boolean ssmOk = entry.validSsm().isEmpty() || entry.validSsm().contains(f.ssm());
        switch (entry.type()) {
            case "BNR": {
                double v = Arinc429.decodeBnr(f.data29(), entry.scale(), entry.offset());
                if (!ssmOk) {
                    return new ValueResult(SSM_INVALID, v, entry.unit(),
                            "SSM=" + f.ssm() + " not in valid set " + entry.validSsm()
                                    + " per " + dict.version() + "; value retained but flagged",
                            hit, false);
                }
                return new ValueResult(OK, v, entry.unit(), "", hit, true);
            }
            case "BCD": {
                Arinc429.BcdResult bcd = Arinc429.decodeBcd(f.data29());
                if (!bcd.ok()) {
                    return new ValueResult(BCD_ILLEGAL, null, entry.unit(),
                            "illegal BCD nibble 0x" + Integer.toHexString(bcd.badNibble()).toUpperCase()
                                    + " at digit " + bcd.badNibbleIndex()
                                    + "; raw data field retained at original position",
                            hit, false);
                }
                double v = bcd.value() * entry.scale() + entry.offset();
                if (!ssmOk) {
                    return new ValueResult(SSM_INVALID, v, entry.unit(),
                            "SSM=" + f.ssm() + " not in valid set " + entry.validSsm()
                                    + " per " + dict.version() + "; value retained but flagged",
                            hit, false);
                }
                return new ValueResult(OK, v, entry.unit(), "", hit, true);
            }
            case "DISC": {
                double v = (double) f.data29();
                if (!ssmOk) {
                    return new ValueResult(SSM_INVALID, v, entry.unit(),
                            "SSM=" + f.ssm() + " not in valid set " + entry.validSsm()
                                    + " per " + dict.version() + "; value retained but flagged",
                            hit, false);
                }
                return new ValueResult(OK, v, entry.unit(), "", hit, true);
            }
            default:
                return new ValueResult(NO_DICT_ENTRY, null, "",
                        "unknown entry type " + entry.type() + " in " + dict.version(), hit, false);
        }
    }
}
