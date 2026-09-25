package avdata.a429.decode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural + engineering decoder.
 *
 * <p>The octal label is only a display of the 8 label bits: transmission-order
 * bits rendered directly, or the document-style bit reversal. Neither changes
 * the underlying word. BNR/BCD/discrete meaning comes solely from the supplied
 * dictionary version.</p>
 */
public final class Decoder {
    private Decoder() {}

    public static DecodedWord decode(int word, LabelDictionary dict) {
        int labelBits = Bits.field(word, 1, 8);
        String labelTransmission = Bits.octal3(labelBits);
        String labelDocument = Bits.octal3(Bits.reverse8(labelBits));
        String sdi = bitPair(word, 9);
        String ssm = bitPair(word, 30);
        int dataField = Bits.field(word, 11, 29);
        boolean parity = Parity.isValid(word);

        List<Interpretation> interpretations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String convention : new String[]{"DOCUMENT_REVERSED", "TRANSMISSION"}) {
            String label = convention.equals("DOCUMENT_REVERSED") ? labelDocument : labelTransmission;
            for (DictionaryEntry entry : dict.candidates(label, sdi)) {
                String key = convention + "|" + entry.encoding + "|" + entry.name;
                if (!seen.add(key)) {
                    continue;
                }
                interpretations.add(interpret(word, label, convention, sdi, ssm, entry, dict.version));
            }
        }
        return new DecodedWord(word, labelBits, labelTransmission, labelDocument,
                sdi, ssm, dataField, parity, interpretations);
    }

    private static Interpretation interpret(int word, String label, String convention,
                                            String sdi, String ssm, DictionaryEntry entry,
                                            String dictVersion) {
        String ssmMeaning = Ssm.meaning(entry.encoding, ssm);
        return switch (entry.encoding) {
            case "BNR" -> bnr(word, label, convention, sdi, ssm, ssmMeaning, entry, dictVersion);
            case "BCD" -> bcd(word, label, convention, sdi, ssm, ssmMeaning, entry, dictVersion);
            case "DISCRETE" -> discrete(word, label, convention, sdi, ssm, ssmMeaning, entry, dictVersion);
            default -> throw new IllegalStateException("unknown encoding " + entry.encoding);
        };
    }

    private static Interpretation bnr(int word, String label, String convention, String sdi,
                                      String ssm, String ssmMeaning, DictionaryEntry e, String ver) {
        Bnr.Result r = Bnr.decode(word, e);
        List<Interpretation.Diagnostic> diags = new ArrayList<>();
        Double value = r.value();
        if (Ssm.isInvalidState("BNR", ssm)) {
            diags.add(new Interpretation.Diagnostic("SSM_INVALID",
                    "SSM " + ssm + " (" + ssmMeaning + ") excludes value from aggregation"));
        }
        if (r.negative()) {
            diags.add(new Interpretation.Diagnostic("BNR_NEGATIVE",
                    "two's-complement sign bit " + e.signBit + " set; value is negative"));
        }
        return new Interpretation("BNR", e.name, e.unit, e.coordinateBasis, value,
                "0b" + r.rawText(), diags, List.of(), ver, label, convention, sdi, ssm, ssmMeaning);
    }

    private static Interpretation bcd(int word, String label, String convention, String sdi,
                                      String ssm, String ssmMeaning, DictionaryEntry e, String ver) {
        int nibbleCount = (e.dataMsb - e.dataLsb + 1) / 4;
        int field = Bits.field(word, e.dataLsb, e.dataMsb);
        Bcd.Result r = Bcd.decode(field, nibbleCount);
        List<Interpretation.Diagnostic> diags = new ArrayList<>(r.diagnostics());
        if (Ssm.isInvalidState("BCD", ssm)) {
            diags.add(new Interpretation.Diagnostic("SSM_INVALID",
                    "SSM " + ssm + " (" + ssmMeaning + ") excludes value from aggregation"));
        }
        return new Interpretation("BCD", e.name, e.unit, e.coordinateBasis, r.value(),
                r.rawText(), diags, List.of(), ver, label, convention, sdi, ssm, ssmMeaning);
    }

    private static Interpretation discrete(int word, String label, String convention, String sdi,
                                           String ssm, String ssmMeaning, DictionaryEntry e, String ver) {
        List<String> states = new ArrayList<>();
        List<Interpretation.Diagnostic> diags = new ArrayList<>();
        for (DictionaryEntry.DiscreteBit db : e.discreteBits) {
            if (Bits.bit(word, db.bit) == 1) {
                states.add(db.meaningWhenOne);
            }
        }
        if (Ssm.isInvalidState("DISCRETE", ssm)) {
            diags.add(new Interpretation.Diagnostic("SSM_INVALID",
                    "SSM " + ssm + " (" + ssmMeaning + ") marks discrete state invalid"));
        }
        return new Interpretation("DISCRETE", e.name, e.unit, e.coordinateBasis,
                (double) Bits.field(word, e.dataLsb, e.dataMsb),
                "0x" + Integer.toHexString(Bits.field(word, e.dataLsb, e.dataMsb)).toUpperCase(),
                diags, states, ver, label, convention, sdi, ssm, ssmMeaning);
    }

    private static String bitPair(int word, int lsb) {
        int lo = Bits.bit(word, lsb);
        int hi = Bits.bit(word, lsb + 1);
        return "" + hi + lo;
    }
}
