package arinc429.service;

import arinc429.domain.Codec;
import arinc429.domain.Json;
import arinc429.domain.Models.DecodedWord;
import arinc429.domain.Models.DictEntry;
import arinc429.domain.Models.Dictionary;
import arinc429.domain.Models.RuleParams;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns one retained 32-bit raw word into a versioned, sourced interpretation. */
public final class DecodeService {

    public DecodedWord decode(String channelId, String rawWordId, String origIndex, String rawText, int rawBits,
                              String branchId, String materialVersion,
                              Dictionary dict, RuleParams rules) {
        String label = Codec.labelOctal(rawBits);
        int sdi = Codec.sdi(rawBits);
        int ssm = Codec.ssm(rawBits);
        int data = Codec.data(rawBits);
        boolean parity = Codec.parityOk(rawBits);

        DictEntry entry = dict.match(label, sdi);
        String encoding = "UNKNOWN";
        String dictName = null;
        Double engineering = null;
        String units = null;
        String rendered = null;
        Map<Integer, String> discrete = new LinkedHashMap<>();
        String diagnostic = null;
        String dictHit = null;

        if (entry != null) {
            encoding = entry.encoding();
            dictName = entry.name();
            units = entry.units();
            dictHit = dict.version() + ":" + entry.labelOctal() + "/" + entry.sdiPattern() + ":" + entry.name();
            if (!parity) {
                diagnostic = "BAD_PARITY";
            }
            switch (entry.encoding()) {
                case "BNR" -> {
                    engineering = Codec.bnrEngineering(data, entry.lsbWeight());
                    rendered = String.format("%+.6f %s", engineering, entry.units());
                    if (Codec.isBnrNegative(data)) {
                        diagnostic = diagnostic == null ? "BNR_NEGATIVE" : diagnostic + ",BNR_NEGATIVE";
                    }
                }
                case "BCD" -> {
                    Codec.BcdResult bcd = Codec.decodeBcd(data, entry.bcdDigits());
                    rendered = bcd.rendered();
                    if (!bcd.illegalPositions().isEmpty()) {
                        diagnostic = (diagnostic == null ? "" : diagnostic + ",")
                                + "BCD_ILLEGAL_DIGIT@positions=" + bcd.illegalPositions();
                        engineering = null;
                    } else {
                        engineering = bcd.numeric();
                    }
                }
                case "DISCRETE" -> {
                    for (Map<String, Object> bit : Codec.decodeDiscrete(data, entry.discreteBits())) {
                        discrete.put((Integer) bit.get("dataBit"), String.valueOf(bit.get("name")));
                    }
                    rendered = discrete.isEmpty() ? "(no bits set)" : discrete.values().toString();
                    engineering = null;
                }
                default -> diagnostic = append(diagnostic, "UNKNOWN_ENCODING");
            }
            String ssmCode = rules.ssmCode(encoding, ssm);
            if (!rules.functional(encoding, ssm)) {
                diagnostic = append(diagnostic, "SSM_" + ssmCode.replaceAll("[^A-Z]", ""));
            }
            DecodedWord w = new DecodedWord(
                    Ids.decodedId(branchId, materialVersion, dict.version(), rules.version(), rawWordId),
                    rawWordId, branchId, channelId, dict.version(), rules.version(), materialVersion,
                    label, sdi, ssm, ssmCode, data, parity, encoding, dictName,
                    engineering, units, rendered, discrete, diagnostic, dictHit);
            return w;
        }

        diagnostic = append(diagnostic, "DICTIONARY_MISS label=" + label);
        return new DecodedWord(
                Ids.decodedId(branchId, materialVersion, dict.version(), rules.version(), rawWordId),
                rawWordId, branchId, channelId, dict.version(), rules.version(), materialVersion,
                label, sdi, ssm, rules.ssmCode("BNR", ssm), data, parity, encoding, null,
                null, null, null, discrete, diagnostic, null);
    }

    public String payload(DecodedWord w) {
        return Json.write(w);
    }

    private String append(String d, String add) {
        return d == null ? add : d + "," + add;
    }
}
