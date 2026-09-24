package org.avionics.review.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one raw word plus the loaded dictionaries into interpretation
 * candidates.
 *
 * <p>Two axes of ambiguity are preserved explicitly instead of being
 * collapsed at import time, so every legal candidate survives until a human
 * or a rule picks one:</p>
 * <ul>
 *   <li>convention: the feed may be 32-bit inverted (bit-order reversal);</li>
 *   <li>dictionary version: a label may decode under more than one version.</li>
 * </ul>
 */
public final class Decoder {

    public static final String CONV_WIRE = "WIRE_LSB_FIRST";
    public static final String CONV_INVERTED = "BIT_REVERSED_32";

    private Decoder() {
    }

    public record CandidateResult(List<EngineeringValue> values, List<String> notes) {
    }

    public static CandidateResult decodeAll(String wordSid, long rawWord,
                                            Map<String, List<DictVersion>> dictionaries) {
        List<String> notes = new ArrayList<>();
        List<EngineeringValue> values = new ArrayList<>();
        boolean wireParity = Arinc429.oddParityOk(rawWord);

        appendForWord(wordSid, rawWord, CONV_WIRE, dictionaries, wireParity, values);

        long reversed = Arinc429.reverse32(rawWord);
        boolean revParity = Arinc429.oddParityOk(reversed);
        int before = values.size();
        appendForWord(wordSid, reversed, CONV_INVERTED, dictionaries, revParity, values);
        boolean reversedHasViable = values.subList(before, values.size()).stream()
                .anyMatch(v -> v.status().equals("OK") || v.status().equals("SSM_INVALID"));
        if (wireParity == revParity && reversedHasViable) {
            notes.add("BIT_REVERSAL_CANDIDATE_PRESERVED");
        }
        return new CandidateResult(values, notes);
    }

    private static void appendForWord(String wordSid, long word, String convention,
                                      Map<String, List<DictVersion>> dictionaries,
                                      boolean parityOk, List<EngineeringValue> out) {
        String label = Arinc429.labelOctal(word);
        List<DictVersion> versions = dictionaries.get(label);
        if (versions == null || versions.isEmpty()) {
            List<String> diag = new ArrayList<>();
            if (!parityOk) {
                diag.add("BAD_PARITY");
            }
            diag.add("NO_DICTIONARY_MATCH:" + label);
            out.add(new EngineeringValue(
                    sid(wordSid, convention, "NONE"), wordSid, convention, "NONE",
                    label, "UNDEFINED_LABEL", "NONE", null, null, "",
                    "BODY_AXIS", "UNDEFINED", diag, Map.of()));
            return;
        }
        for (DictVersion dv : versions) {
            out.add(decodeWithVersion(wordSid, word, convention, label, dv, parityOk));
        }
    }

    private static EngineeringValue decodeWithVersion(String wordSid, long word, String convention,
                                                      String label, DictVersion dv,
                                                      boolean parityOk) {
        DictDef def = dv.defFor(label);
        List<String> diag = new ArrayList<>();
        if (!parityOk) {
            diag.add("BAD_PARITY");
        }
        Map<String, String> discrete = new LinkedHashMap<>();
        String status = "OK";
        Double numeric = null;
        String text;

        int ssm = Arinc429.ssm(word);
        boolean ssmValid = Arinc429.ssmIsValid(ssm, def.encoding());
        if (!ssmValid) {
            diag.add("SSM_INVALID:" + Arinc429.ssmName(ssm, def.encoding()));
            status = "SSM_INVALID";
        }

        if (def.isBnr()) {
            double ev = "SIGNED_SYMMETRIC".equals(dv.unitSign())
                    ? Arinc429.bnrSigned(word, def.msb(), def.lsb(), def.range())
                    : Arinc429.bnrUnsigned(word, def.msb(), def.lsb(), def.range());
            numeric = round(ev);
            text = numeric + " " + def.unit();
        } else if (def.isBcd()) {
            StringBuilder sb = new StringBuilder();
            int digitCount = def.fieldBits() / 4;
            boolean illegal = false;
            for (int d = 0; d < digitCount; d++) {
                int nibbleMsb = def.msb() - d * 4;
                int nibbleLsb = nibbleMsb - 3;
                int digit = Arinc429.bcdDigit(word, nibbleMsb, nibbleLsb);
                if (digit == 0x10) {
                    illegal = true;
                    sb.append('?');
                    diag.add("BCD_ILLEGAL_DIGIT@bits" + nibbleMsb + "-" + nibbleLsb);
                } else {
                    sb.append(digit);
                }
                int positionFromRight = digitCount - 1 - d;
                if (def.impliedDecimals() > 0 && positionFromRight == def.impliedDecimals()) {
                    sb.append('.');
                }
            }
            if (def.impliedDecimals() > 0 && sb.indexOf(".") < 0) {
                sb.insert(sb.length() - def.impliedDecimals(), '.');
            }
            text = sb + " " + def.unit();
            if (illegal) {
                status = "BCD_ILLEGAL";
            } else {
                try {
                    numeric = Double.parseDouble(sb.toString().trim());
                } catch (NumberFormatException ignore) {
                    numeric = null;
                }
            }
        } else if (def.isDiscrete()) {
            List<String> set = new ArrayList<>();
            for (Map.Entry<Integer, String> e : def.discreteBits().entrySet()) {
                boolean on = Arinc429.bitAt(word, e.getKey());
                discrete.put(e.getValue(), on ? "1" : "0");
                if (on) {
                    set.add(e.getValue());
                }
            }
            text = set.isEmpty() ? "(none)" : String.join(",", set);
        } else {
            diag.add("UNSUPPORTED_ENCODING:" + def.encoding());
            status = "UNSUPPORTED";
            text = null;
        }

        if (parityOk && "OK".equals(status) == false) {
            // keep more specific status already set
        }
        if (!parityOk && "OK".equals(status)) {
            status = "BAD_PARITY";
        }

        return new EngineeringValue(
                sid(wordSid, convention, dv.versionId()), wordSid, convention, dv.versionId(),
                label, def.name(), def.encoding(), text, numeric, def.unit(),
                dv.coordinateBasis(), status, diag, discrete);
    }

    public static String sid(String wordSid, String convention, String dictVersion) {
        return "ev:" + wordSid + ":" + convention + ":" + dictVersion;
    }

    private static double round(double v) {
        return Math.round(v * 1e6d) / 1e6d;
    }
}
