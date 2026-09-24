package a429.domain;

import java.util.Map;

/**
 * A versioned label dictionary. Interpretation (BNR/BCD/discrete) is resolved
 * only against a named dictionary version; source words never carry it.
 */
public record Dictionary(
        String version,
        String releasedAt,
        Map<String, LabelEntry> entriesByKey) {

    public static String key(String labelOctal, int sdi) {
        return labelOctal + "#" + sdi;
    }

    public LabelEntry lookup(String labelOctal, int sdi) {
        LabelEntry exact = entriesByKey.get(key(labelOctal, sdi));
        if (exact != null) {
            return exact;
        }
        return entriesByKey.get(key(labelOctal, 0));
    }
}
