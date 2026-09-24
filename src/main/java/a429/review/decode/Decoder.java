package a429.review.decode;

import a429.review.domain.Arinc429;
import a429.review.domain.Dictionary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure derivation layer: raw word + frozen dictionary version -> interpretation.
 * No input is ever "cleaned" here; values that cannot be converted stay in place
 * with diagnostics, and every legal dictionary candidate is retained.
 */
public final class Decoder {

    public record DigitView(int nibbleIndex, int bitHi, int bitLo, int value, boolean legal) {
    }

    public record Candidate(String entryName,
                            Dictionary.DataType dataType,
                            String unit,
                            String ssmMeaning,
                            boolean ssmNormal,
                            boolean convertible,
                            boolean aggregateEligible,
                            Double bnrValue,
                            List<DigitView> bcdDigits,
                            String bcdText,
                            List<String> discreteSet,
                            List<String> diagnostics,
                            Map<String, Object> details) {
    }

    public record Interpretation(long word,
                                 String octalLabel,
                                 int sdi,
                                 int ssm,
                                 int dataField,
                                 boolean parityOk,
                                 String dictionaryVersion,
                                 String decoderVersion,
                                 List<Candidate> candidates,
                                 List<String> diagnostics) {
    }

    private final Dictionary dictionary;

    public Decoder(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    public Interpretation interpret(long word) {
        String oct = Arinc429.octalLabel(word);
        int sdi = Arinc429.sdi(word);
        int ssm = Arinc429.ssm(word);
        int data = Arinc429.data(word);
        boolean parityOk = Arinc429.oddParityOk(word);

        List<Dictionary.Entry> entries = dictionary.match(oct, sdi);
        List<Candidate> candidates = new ArrayList<>();
        List<String> topDiag = new ArrayList<>();
        if (!parityOk) {
            topDiag.add("BAD_PARITY: word fails odd parity and is excluded from aggregate values");
        }
        if (entries.isEmpty()) {
            topDiag.add("NO_DICTIONARY_MATCH: label " + oct + " SDI " + sdi
                    + " not found in dictionary " + dictionary.version());
        }
        for (Dictionary.Entry e : entries) {
            candidates.add(decodeEntry(word, e, parityOk, ssm, data));
        }
        return new Interpretation(word, oct, sdi, ssm, data, parityOk,
                dictionary.version(), Arinc429.DECODER_VERSION, List.copyOf(candidates), List.copyOf(topDiag));
    }

    private Candidate decodeEntry(long word, Dictionary.Entry e, boolean parityOk, int ssm, int data) {
        String ssmMeaning = Arinc429.ssmMeaning(e.dataType.name(), ssm);
        boolean ssmNormal = "NORMAL".equals(ssmMeaning);
        List<String> diag = new ArrayList<>();
        boolean convertible;
        Double bnr = null;
        List<DigitView> digits = List.of();
        String bcdText = null;
        List<String> discrete = List.of();
        Map<String, Object> details = new LinkedHashMap<>();

        switch (e.dataType) {
            case BNR -> {
                int width = e.msb - e.lsb + 1;
                int dataBits = Arinc429.bits(word, e.msb, e.lsb);
                long signMask = 1L << (width - 1);
                long signed = dataBits;
                if ((signed & signMask) != 0) {
                    signed -= 1L << width; // two's complement, handles negative BNR
                }
                bnr = signed * e.resolutionLsb;
                details.put("signedRawBits", signed);
                details.put("resolutionLsb", e.resolutionLsb);
                details.put("bitWidth", width);
                convertible = true;
            }
            case BCD -> {
                List<DigitView> dvs = new ArrayList<>();
                int nibbleCount = (29 - e.lsb + 1) / 4;
                for (int i = 0; i < nibbleCount; i++) {
                    int lo = e.lsb + i * 4;
                    int hi = lo + 3;
                    int v = Arinc429.bits(word, hi, lo);
                    boolean legal = v <= 9;
                    if (!legal) {
                        diag.add("ILLEGAL_BCD_DIGIT: nibble bits " + lo + "-" + hi + " = "
                                + Integer.toHexString(v).toUpperCase() + " (A-F is not a decimal digit)");
                    }
                    dvs.add(new DigitView(i, hi, lo, v, legal));
                }
                // most significant digit occupies remaining bits (27-29 for an 11..29 field)
                int used = nibbleCount * 4;
                int msbHi = 29;
                int msbLo = e.lsb + used;
                if (msbHi >= msbLo) {
                    int width = msbHi - msbLo + 1;
                    int v = Arinc429.bits(word, msbHi, msbLo);
                    boolean legal = v <= (1 << width) - 1 && v <= 9;
                    if (!legal) {
                        diag.add("ILLEGAL_BCD_DIGIT: leading nibble bits " + msbLo + "-" + msbHi
                                + " = " + Integer.toHexString(v).toUpperCase());
                    }
                    dvs.add(new DigitView(dvs.size(), msbHi, msbLo, v, legal));
                }
                digits = List.copyOf(dvs);
                boolean anyIllegal = dvs.stream().anyMatch(d -> !d.legal);
                bcdText = renderBcd(dvs, anyIllegal);
                if (anyIllegal) {
                    diag.add("UNCONVERTIBLE_BCD: original nibbles retained, value not normalized");
                }
                convertible = !anyIllegal;
            }
            case DISCRETE -> {
                List<String> set = new ArrayList<>();
                for (int bit = e.lsb; bit <= 29; bit++) {
                    if (Arinc429.bits(word, bit, bit) == 1) {
                        int idx = bit - e.lsb;
                        String name = idx < e.discreteNames.size()
                                ? e.discreteNames.get(idx)
                                : ("BIT_" + bit);
                        set.add(name + "@bit" + bit);
                    }
                }
                discrete = List.copyOf(set);
                details.put("bitNames", e.discreteNames);
                convertible = true;
            }
        }

        boolean eligible = parityOk && ssmNormal && convertible;
        if (parityOk && !ssmNormal) {
            diag.add("SSM_" + ssmMeaning + ": word retained but excluded from aggregate values");
        }
        if (!parityOk) {
            diag.add("EXCLUDED_FROM_AGGREGATE: bad parity");
        }
        return new Candidate(e.name, e.dataType, e.unit, ssmMeaning, ssmNormal,
                convertible, eligible, bnr, digits, bcdText, discrete, List.copyOf(diag), details);
    }

    private String renderBcd(List<DigitView> dvs, boolean anyIllegal) {
        StringBuilder sb = new StringBuilder();
        for (int i = dvs.size() - 1; i >= 0; i--) {
            DigitView d = dvs.get(i);
            sb.append(d.legal ? Character.forDigit(d.value, 10) : '?');
        }
        return sb.toString();
    }
}
