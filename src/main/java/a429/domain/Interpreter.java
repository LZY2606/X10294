package a429.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Versioned word interpretation.
 *
 * Derivation rules (frozen for the bench, also recorded as machine-readable events):
 *  - label octal presentation never changes underlying bits (wire byte vs canonical byte);
 *  - BNR/BCD/discrete interpretation is selected by the dictionary version;
 *  - bad-parity words are decoded for display but excluded from aggregate values.
 */
public class Interpreter {

    public Decoded decode(RawWord word, DictVersion dict, String labelConvention) {
        long raw = word.raw();
        int labelByte = Bits.labelByte(raw);
        int canonicalByte = Bits.reverse8(labelByte);
        String wireOctal = Bits.octal3(labelByte);
        String canonicalOctal = Bits.octal3(canonicalByte);
        int sdi = Bits.sdi(raw);
        int ssm = Bits.ssm(raw);
        int data = Bits.dataField(raw);
        boolean parityOk = Bits.oddParityOk(raw);

        List<String> diagnostics = new ArrayList<>();
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(new FieldSpan("parity", "PARITY (bit 32)", 32, 32, Bits.parityBit(raw),
                parityOk ? "odd parity ok" : "ODD PARITY FAIL"));
        spans.add(new FieldSpan("ssm", "SSM (bits 30-31)", 31, 30, ssm, ""));
        spans.add(new FieldSpan("data", "DATA (bits 11-29)", 29, 11, data, ""));
        spans.add(new FieldSpan("sdi", "SDI (bits 9-10)", 10, 9, sdi, ""));
        spans.add(new FieldSpan("label", "LABEL (bits 1-8, LSB-first on wire)", 8, 1, labelByte,
                "wire=" + wireOctal + " octal / canonical=" + canonicalOctal + " octal"));

        if (!parityOk) {
            diagnostics.add("PARITY_FAIL: word kept at in-place sequence, excluded from aggregate values");
        }

        DictEntry entry = dict.entriesByCanonicalLabel().get(canonicalOctal);
        boolean labelHit = entry != null;
        if (!labelHit) {
            diagnostics.add("LABEL_MISS: canonical label " + canonicalOctal
                    + " not present in dictionary " + dict.dictId() + "@" + dict.version());
        }

        Decoded.EngineeringValue eng = null;
        String kind = null;
        String entryName = null;
        if (entry != null) {
            if (entry.sdiMatch() != null && entry.sdiMatch() != sdi) {
                diagnostics.add("SDI_MISMATCH: entry requires SDI " + entry.sdiMatch()
                        + ", word carries SDI " + sdi);
            }
            kind = entry.kind();
            entryName = entry.name();
            eng = switch (entry.kind()) {
                case "BNR" -> decodeBnr(raw, data, ssm, entry, spans, diagnostics);
                case "BCD" -> decodeBcd(raw, ssm, entry, spans, diagnostics);
                case "DISCRETE" -> decodeDiscrete(raw, ssm, entry, spans, diagnostics);
                default -> new Decoded.EngineeringValue(false, null, entry.unit(), entry.datum(),
                        "unknown kind " + entry.kind(), false);
            };
        }

        // The convention changes presentation only; both octal forms are always carried.
        String presented = "WIRE".equalsIgnoreCase(labelConvention) ? wireOctal : canonicalOctal;
        if (labelByte != canonicalByte) {
            diagnostics.add("LABEL_PRESENTATION: shown as " + presented
                    + " octal (" + labelConvention.toLowerCase() + "); underlying byte unchanged (0x"
                    + String.format("%02X", labelByte) + ")");
        }

        return new Decoded(wireOctal, canonicalOctal, labelByte, canonicalByte, sdi, ssm, data,
                parityOk, labelHit, dict.dictId() + "@" + dict.version(), entryName, kind, eng,
                spans, List.copyOf(diagnostics));
    }

    private Decoded.EngineeringValue decodeBnr(long raw, int data, int ssm, DictEntry entry,
                                               List<FieldSpan> spans, List<String> diagnostics) {
        int signBit = entry.bnrSignArinc() == null ? 29 : entry.bnrSignArinc();
        int width = signBit - 11 + 1;
        long signed = Bits.signExtend(Bits.field(raw, signBit, 11), width);
        double value = signed * entry.resolution();
        spans.add(new FieldSpan("bnr_sign", entry.name() + " sign (bit " + signBit + ")",
                signBit, signBit, (int) ((signed >>> (width - 1)) & 1),
                signed < 0 ? "negative" : "positive"));
        spans.add(new FieldSpan("bnr_mag", entry.name() + " magnitude", signBit - 1, 11,
                (int) (signed & ((1L << (width - 1)) - 1)),
                "resolution " + entry.resolution() + " " + entry.unit()));
        boolean valid = ssmValid(ssm, entry.ssmScheme(), diagnostics);
        if (signed < 0) {
            diagnostics.add("BNR_NEGATIVE: two's-complement value " + signed + " * "
                    + entry.resolution() + " = " + value + " " + entry.unit());
        }
        return new Decoded.EngineeringValue(true, value, entry.unit(), entry.datum(),
            trim(value) + " " + entry.unit() + " " + entry.datum(), valid);
    }

    private Decoded.EngineeringValue decodeBcd(long raw, int ssm, DictEntry entry,
                                               List<FieldSpan> spans, List<String> diagnostics) {
        long magnitude = 0;
        boolean illegal = false;
        for (DictEntry.BcdDigit digit : entry.bcdDigits()) {
            int nibble = Bits.field(raw, digit.msb(), digit.lsb());
            boolean bad = nibble > 9;
            illegal |= bad;
            spans.add(new FieldSpan("bcd_digit",
                    entry.name() + " x" + digit.weight() + " (bits " + digit.msb() + "-" + digit.lsb() + ")",
                    digit.msb(), digit.lsb(), nibble, bad ? "ILLEGAL BCD DIGIT" : "0x" + Integer.toHexString(nibble)));
            if (bad) {
                diagnostics.add("BCD_ILLEGAL_DIGIT: bits " + digit.msb() + "-" + digit.lsb()
                        + " = " + String.format("%X", nibble) + " (0b" + String.format("%4s",
                        Integer.toBinaryString(nibble)).replace(' ', '0') + ") is not 0-9");
            } else {
                magnitude += (long) nibble * digit.weight();
            }
        }
        boolean negative = "BCD".equals(entry.ssmScheme()) && ssm == 0b10;
        boolean valid = ssmValid(ssm, entry.ssmScheme(), diagnostics);
        double value = (negative ? -1 : 1) * magnitude;
        if (illegal) {
            // The unconvertible value stays in place with a diagnostic instead of being dropped.
            return new Decoded.EngineeringValue(false, null, entry.unit(), entry.datum(),
                    "unconvertible (illegal BCD digit; raw nibbles kept in bit view)", valid);
        }
        return new Decoded.EngineeringValue(true, value, entry.unit(), entry.datum(),
                trim(value) + " " + entry.unit() + " " + entry.datum(), valid);
    }

    private Decoded.EngineeringValue decodeDiscrete(long raw, int ssm, DictEntry entry,
                                                    List<FieldSpan> spans, List<String> diagnostics) {
        List<String> on = new ArrayList<>();
        for (DictEntry.DiscreteBit bit : entry.discreteBits()) {
            int v = Bits.field(raw, bit.arincBit(), bit.arincBit());
            spans.add(new FieldSpan("discrete", bit.code() + " (bit " + bit.arincBit() + ")",
                    bit.arincBit(), bit.arincBit(), v, v == 1 ? bit.meaningWhenOne() : "off"));
            if (v == 1) {
                on.add(bit.code());
            }
        }
        boolean valid = ssmValid(ssm, entry.ssmScheme(), diagnostics);
        return new Decoded.EngineeringValue(true, null, entry.unit(), entry.datum(),
                on.isEmpty() ? "no discrete bits set" : String.join(",", on), valid);
    }

    private boolean ssmValid(int ssm, String scheme, List<String> diagnostics) {
        if (scheme == null || "NONE".equals(scheme)) {
            return true;
        }
        return switch (scheme) {
            case "BNR", "DISCRETE" -> {
                boolean valid = ssm == 0b11;
                if (!valid) {
                    diagnostics.add("SSM_INVALID: scheme=" + scheme + " code=0b"
                            + String.format("%2s", Integer.toBinaryString(ssm)).replace(' ', '0')
                            + " meaning=" + Map.of(0, "NO_COMPUTED_DATA", 1, "FUNCTIONAL_TEST",
                            2, "FAILURE_WARNING", 3, "NORMAL_OPERATION").get(ssm));
                }
                yield valid;
            }
            case "BCD" -> {
                boolean valid = ssm == 0b00 || ssm == 0b10;
                if (!valid) {
                    diagnostics.add("SSM_INVALID: scheme=BCD code=0b"
                            + String.format("%2s", Integer.toBinaryString(ssm)).replace(' ', '0')
                            + " meaning=" + Map.of(0, "PLUS", 1, "NO_COMPUTED_DATA",
                            2, "MINUS", 3, "FUNCTIONAL_TEST").get(ssm));
                }
                yield valid;
            }
            default -> true;
        };
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
