package arinc429.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable domain value objects shared by services, storage and the UI. */
public final class Models {
    private Models() {
    }

    public record RawWord(String id, String origIndex, String receiveTimeUs, String channel,
                          String rawText, String materialVersion) {
    }

    /**
     * @param encoding BNR / BCD / DISCRETE
     * @param lsbWeight BNR weight of the data LSB (engineering units per count)
     * @param units engineering unit or coordinate datum, e.g. "ft MSL"
     * @param bcdDigits number of BCD nibbles in the data field
     * @param discreteBits data-LSB-relative bit index (0..18) -> signal name
     */
    public record DictEntry(String labelOctal, String sdiPattern, String name, String encoding,
                            Double lsbWeight, String units, Integer bcdDigits,
                            Map<Integer, String> discreteBits, String note) {
    }

    public record Dictionary(String version, Map<String, DictEntry> entries) {
        /** Key is label octal + "/" + sdi pattern ("00","01","10","11","**"). */
        public DictEntry match(String labelOctal, int sdi) {
            DictEntry e = entries.get(labelOctal + "/" + String.format("%02d", sdi));
            if (e != null) {
                return e;
            }
            return entries.get(labelOctal + "/**");
        }
    }

    /** Parameters supplied by a named rule version. */
    public record RuleParams(String version, double rateTolerance, long acquisitionGapUs,
                             Map<String, Map<Integer, String>> ssmTable) {
        public String ssmCode(String encoding, int ssm) {
            Map<Integer, String> fam = ssmTable.getOrDefault(encoding, ssmTable.get("BNR"));
            return fam == null ? "?" : fam.getOrDefault(ssm, "?");
        }

        public boolean functional(String encoding, int ssm) {
            return "NO".equals(ssmCode(encoding, ssm));
        }
    }

    /** Fully interpreted word; every derived field remembers its source word. */
    public record DecodedWord(String id, String rawWordId, String branchId, String channel,
                              String dictionaryVersion,
                              String ruleVersion, String materialVersion,
                              String labelOctal, int sdi, int ssm, String ssmCode, int dataDecimal,
                              boolean parityOk, String encoding, String dictName,
                              Double engineering, String units, String rendered,
                              Map<Integer, String> discrete, String diagnostic, String dictHit) {
    }

    /** One legal rate segment candidate with its supporting word span. */
    public record RateSegmentCandidate(String id, String channel, int startOrig, int endOrig,
                                       String startUs, String endUs, String rateBps,
                                       double nominalGapUs, double observedGapUs,
                                       boolean boundary, String reason) {
    }

    public record RateAnalysis(String id, String branchId, String ruleVersion, String materialVersion,
                               String channel, Map<String, Object> detail) {
    }

    /** Aggregated engineering value per channel/label on a branch. */
    public record Aggregate(String id, String branchId, String channel, String labelOctal,
                            String encoding, String dictionaryVersion, int memberCount,
                            int excludedParity, int excludedSsm, Double last, String units,
                            Double min, Double max, boolean ambiguous) {
    }
}
