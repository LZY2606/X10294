package a429.decode;

import a429.domain.Dictionary;
import a429.domain.EncodingType;
import a429.domain.LabelEntry;
import a429.domain.Quantity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a specific dictionary version to a decoded word. The raw 19-bit data
 * field is always carried through; unconvertible values stay in place with a
 * diagnostic instead of being dropped.
 */
public final class Interpreter {

    private static final int DATA_BITS = 19;

    public EngineeringResult interpret(DecodeResult decoded, Dictionary dictionary) {
        var fields = decoded.fields();
        LabelEntry entry = dictionary.lookup(fields.labelOctal(), fields.sdi());
        int ssm = fields.ssm();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("dataBits", Bits429.dataBits(decoded.word()));
        raw.put("dataBinary", String.format("%19s", Integer.toBinaryString(Bits429.dataBits(decoded.word()))).replace(' ', '0'));
        raw.put("wireHex", fields.wireHex());

        if (entry == null) {
            return new EngineeringResult(dictionary.version(), fields.labelOctal(), null, null,
                    Quantity.invalid("", "", "no dictionary entry for label " + fields.labelOctal()
                            + " SDI " + fields.sdi() + " in " + dictionary.version()),
                    null, false, fields.parityValid(), false,
                    List.of(), raw);
        }

        EncodingType enc = entry.encoding();
        boolean ssmInvalid = Ssm.invalid(enc, ssm);
        Quantity quantity;
        List<String> discrete = new ArrayList<>();

        switch (enc) {
            case BNR -> quantity = interpretBnr(Bits429.dataBits(decoded.word()), entry, ssmInvalid);
            case BCD -> quantity = interpretBcd(Bits429.dataBits(decoded.word()), entry, ssmInvalid);
            case DISCRETE -> {
                quantity = Quantity.of(Bits429.dataBits(decoded.word()), "bits", entry.basis());
                fillDiscrete(decoded.word(), entry, discrete);
            }
            default -> throw new IllegalStateException(enc.toString());
        }

        boolean enters = fields.parityValid() && !ssmInvalid && quantity.valid();
        return new EngineeringResult(dictionary.version(), fields.labelOctal(), entry.name(),
                enc.name(), quantity, Ssm.meaning(enc, ssm), ssmInvalid, fields.parityValid(),
                enters, List.copyOf(discrete), raw);
    }

    private Quantity interpretBnr(int data, LabelEntry entry, boolean ssmInvalid) {
        if (ssmInvalid) {
            return Quantity.invalid(entry.unit(), entry.basis(), "SSM marks data invalid");
        }
        int valueBits = data & ((1 << DATA_BITS) - 1);
        if (entry.signed() && (valueBits & (1 << (DATA_BITS - 1))) != 0) {
            valueBits -= 1 << DATA_BITS;
        }
        double value = valueBits * entry.resolution();
        return Quantity.of(value, entry.unit(), entry.basis());
    }

    private Quantity interpretBcd(int data, LabelEntry entry, boolean ssmInvalid) {
        if (ssmInvalid) {
            return Quantity.invalid(entry.unit(), entry.basis(), "SSM marks data invalid");
        }
        int nibbleCount = DATA_BITS / 4; // 4 full nibbles (16 bits); bit 29 unused
        long value = 0;
        for (int i = nibbleCount - 1; i >= 0; i--) {
            int nibble = (data >>> (i * 4)) & 0xF;
            if (nibble > 9) {
                return Quantity.invalid(entry.unit(), entry.basis(),
                        "illegal BCD digit 0x" + Integer.toHexString(nibble).toUpperCase()
                                + " at nibble " + (nibbleCount - i) + "; raw=0x"
                                + String.format("%05X", data));
            }
            value = value * 10 + nibble;
        }
        return Quantity.of(value * entry.resolution(), entry.unit(), entry.basis());
    }

    private void fillDiscrete(int word, LabelEntry entry, List<String> out) {
        if (entry.bitNames() == null) {
            return;
        }
        entry.bitNames().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int level = Bits429.dataBit(word, e.getKey());
                    out.add(e.getKey() + ":" + e.getValue() + "=" + level);
                });
    }
}
