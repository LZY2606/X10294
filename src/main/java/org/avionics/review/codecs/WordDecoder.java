package org.avionics.review.codecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.avionics.review.domain.Bits;
import org.avionics.review.domain.DecodedField;
import org.avionics.review.domain.DictVersion;
import org.avionics.review.domain.Encoding;
import org.avionics.review.domain.EngineeringValue;
import org.avionics.review.domain.LabelDef;
import org.avionics.review.domain.Orientation;
import org.avionics.review.domain.WordCandidate;

/** Stateless decoder: turns one raw 32-bit word + a dictionary version into candidates. */
public final class WordDecoder {

    private WordDecoder() {
    }

    public static List<WordCandidate> candidates(int raw, DictVersion dict) {
        List<WordCandidate> out = new ArrayList<>(2);
        out.add(decode(raw, Orientation.NORMAL, dict));
        out.add(decode(Bits.reverse32(raw), Orientation.REVERSED, dict));
        return out;
    }

    public static WordCandidate decode(int raw, Orientation orientation, DictVersion dict) {
        int labelValue = Bits.labelValue(raw);
        String label = Bits.labelOctal(labelValue);
        int sdi = Bits.field(raw, 9, 10);
        int ssm = Bits.field(raw, 29, 30);
        boolean parityOk = Bits.oddParityOk(raw);

        LabelDef def = dict.lookup(label, sdi);
        boolean dictHit = def != null;

        String ssmState;
        boolean ssmValid;
        if (def != null && def.encoding() == Encoding.DISCRETE) {
            ssmState = switch (ssm) {
                case 0 -> "NORMAL";
                case 1, 2, 3 -> "INVALID";
                default -> throw new IllegalStateException();
            };
            ssmValid = ssm == 0;
        } else {
            ssmState = switch (ssm) {
                case 0 -> "FAILURE_WARNING";
                case 1 -> "NO_COMPUTED_DATA";
                case 2 -> "FUNCTIONAL_TEST";
                case 3 -> "NORMAL_OPERATION";
                default -> throw new IllegalStateException();
            };
            ssmValid = ssm == 3;
        }

        List<DecodedField> fields = new ArrayList<>();
        fields.add(new DecodedField("label", label, List.of(1, 2, 3, 4, 5, 6, 7, 8), null));
        fields.add(new DecodedField("sdi", String.format("%02d", sdi), List.of(9, 10), null));
        fields.add(new DecodedField("data", String.format("%05X", Bits.field(raw, 11, 28)),
                List.of(11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28),
                null));
        fields.add(new DecodedField("ssm", Integer.toBinaryString(ssm), List.of(29, 30),
                ssmValid ? null : "SSM " + ssmState + ": data not valid"));
        fields.add(new DecodedField("parity", parityOk ? "odd-ok" : "odd-fail", List.of(31, 32),
                parityOk ? null : "odd parity check failed"));

        EngineeringValue eng;
        if (!dictHit) {
            eng = new EngineeringValue(null, null, null,
                    List.of("no dictionary hit for label " + label + " in " + dict.version()));
        } else if (def.encoding() == Encoding.BNR) {
            eng = decodeBnr(raw, def);
        } else if (def.encoding() == Encoding.BCD) {
            eng = decodeBcd(raw, def);
        } else {
            eng = decodeDiscrete(raw, def);
        }

        String cid = "cand:" + orientation.name().toLowerCase() + ":" + label + ":" + dict.version();
        return new WordCandidate(cid, orientation, raw, parityOk, label, labelValue, sdi, ssm,
                ssmState, ssmValid, String.format("%08X", raw), fields, eng, dict.version(), dictHit);
    }

    private static EngineeringValue decodeBnr(int raw, LabelDef def) {
        List<String> diag = new ArrayList<>();
        int msb = def.msb() == null ? 28 : def.msb();
        int lsb = def.lsb() == null ? 11 : def.lsb();
        int signBit = def.signBit() == null ? 29 : def.signBit();
        int width = msb - lsb + 1;
        int magnitude = Bits.field(raw, lsb, msb);
        boolean negative = Bits.field(raw, signBit, signBit) == 1;
        long signed;
        if (negative) {
            signed = magnitude - (1L << width);
        } else {
            signed = magnitude;
        }
        double resolution = def.resolution() == null ? 1.0 : def.resolution();
        double value = signed * resolution;
        return new EngineeringValue(value, def.unit(), null, diag.isEmpty() ? List.of() : diag);
    }

    private static EngineeringValue decodeBcd(int raw, LabelDef def) {
        List<String> diag = new ArrayList<>();
        long value = 0;
        boolean illegal = false;
        List<int[]> nibbles = def.nibbles();
        if (nibbles == null || nibbles.isEmpty()) {
            nibbles = List.of(new int[]{27, 28}, new int[]{23, 24}, new int[]{19, 20},
                    new int[]{15, 16}, new int[]{11, 12});
        }
        int digitIndex = 0;
        for (int[] range : nibbles) {
            int nibble = Bits.field(raw, range[1], range[0]);
            if (nibble > 9) {
                illegal = true;
                diag.add("illegal BCD digit 0x" + Integer.toHexString(nibble).toUpperCase()
                        + " at digit " + (digitIndex + 1) + " (bits " + range[1] + ".." + range[0] + ")");
            } else {
                value = value * 10 + nibble;
            }
            digitIndex++;
        }
        if (def.signBit() != null) {
            int sign = Bits.field(raw, def.signBit(), def.signBit());
            if (sign == 1 && !illegal) {
                value = -value;
            }
        }
        Double v = illegal ? null : (double) value;
        return new EngineeringValue(v, def.unit(), null, diag);
    }

    private static EngineeringValue decodeDiscrete(int raw, LabelDef def) {
        List<EngineeringValue.BitSignal> signals = new ArrayList<>();
        if (def.bits() != null) {
            for (Map.Entry<String, Integer> e : def.bits().entrySet()) {
                signals.add(new EngineeringValue.BitSignal(e.getKey(), e.getValue(),
                        Bits.field(raw, e.getValue(), e.getValue())));
            }
        }
        return new EngineeringValue(null, def.unit(), signals, List.of());
    }
}
