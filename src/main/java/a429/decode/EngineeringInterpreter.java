package a429.decode;

import java.util.ArrayList;
import java.util.List;

/** Applies a dictionary version to a structurally decoded word. */
public final class EngineeringInterpreter {
    private EngineeringInterpreter() { }

    public static EngineeringValue interpret(DecodedWord d, Dictionary dict, String coordinateBasis) {
        LabelDef def = dict.lookup(d.labelOctal());
        List<String> diag = new ArrayList<>();
        boolean bnr = def.encoding() == Encoding.BNR;
        String ssm = WordDecoder.ssmState(d.ssmRaw(), def.encoding());
        if (!d.parityOk()) {
            diag.add("BAD_PARITY: word excluded from aggregate values");
        }
        if (def.encoding() == Encoding.NONE) {
            diag.add("UNKNOWN_LABEL: no dictionary entry for octal label " + d.labelOctal()
                    + " in dictionary " + dict.version());
            return new EngineeringValue(d.wordId(), d.labelOctal(), dict.version(),
                    Encoding.NONE, ssm, false, null, "—", "", coordinateBasis, diag);
        }
        if (!"NOMINAL".equals(ssm)) {
            diag.add("SSM_" + ssm + ": non-nominal source state");
        }

        Double numeric = null;
        String text;
        switch (def.encoding()) {
            case BNR -> {
                long raw19 = d.dataRaw() & 0x7FFFFL;
                if ((raw19 & 0x40000L) != 0) {
                    raw19 -= 0x80000L; // two's complement -> signed
                }
                double v = raw19 * def.bnrResolution();
                numeric = v;
                text = format(v) + " " + def.unit();
            }
            case BCD -> {
                BcdResult r = decodeBcd(d.dataRaw(), def.bcdDecimals());
                if (r.illegalNibbles.isEmpty()) {
                    numeric = r.value;
                    text = format(r.value) + " " + def.unit();
                } else {
                    diag.add("BCD_ILLEGAL_DIGIT at nibble position(s) " + r.illegalNibbles
                            + ": value left at source, excluded from aggregation");
                    text = "invalid BCD nibbles " + r.illegalNibbles + " (raw 0x"
                            + Integer.toHexString(d.dataRaw()) + ")";
                }
            }
            case DISCRETE -> {
                StringBuilder sb = new StringBuilder();
                for (LabelDef.DiscreteBit db : def.discreteBits()) {
                    int bit = Bits.bit(d.rawBits(), db.dataBit());
                    String meaning = bit == 1 ? db.meaningTrue() : db.meaningFalse();
                    if (!sb.isEmpty()) {
                        sb.append("; ");
                    }
                    sb.append("bit").append(db.dataBit()).append('=').append(bit)
                            .append(' ').append(meaning);
                }
                text = sb.isEmpty() ? "(no discrete mapping)" : sb.toString();
            }
            default -> text = "—";
        }

        boolean usable = d.parityOk()
                && "NOMINAL".equals(ssm)
                && diag.stream().noneMatch(x -> x.startsWith("BCD_ILLEGAL_DIGIT"));
        return new EngineeringValue(d.wordId(), d.labelOctal(), dict.version(),
                def.encoding(), ssm, usable, numeric, text, def.unit(), coordinateBasis, diag);
    }

    private record BcdResult(double value, List<Integer> illegalNibbles) { }

    /**
     * Four BCD digits live in bits 26..11: nibble 0 (bits 14..11) is the least
     * significant digit. Bits 29..27 carry sign/status and are not digits here.
     */
    private static BcdResult decodeBcd(int data19, int decimals) {
        List<Integer> illegal = new ArrayList<>();
        long magnitude = 0;
        for (int n = 0; n < 4; n++) {
            int nibble = (data19 >>> (4 * n)) & 0xF;
            if (nibble > 9) {
                illegal.add(n);
            } else {
                magnitude += (long) nibble * pow10(n);
            }
        }
        boolean negative = ((data19 >>> 18) & 1) == 1; // bit 29
        if (negative) {
            magnitude = -magnitude;
        }
        double value = magnitude;
        for (int i = 0; i < decimals; i++) {
            value /= 10.0;
        }
        return new BcdResult(value, illegal);
    }

    private static long pow10(int n) {
        long p = 1;
        for (int i = 0; i < n; i++) {
            p *= 10;
        }
        return p;
    }

    private static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format("%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
