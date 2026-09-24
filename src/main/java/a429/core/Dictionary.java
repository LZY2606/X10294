package a429.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A versioned label dictionary. BNR/BCD/discrete interpretation is decided
 * exclusively by the dictionary version pinned to a branch.
 */
public final class Dictionary {

    public record Entry(
            String labelOctal,
            String mnemonic,
            String type,
            double scale,
            double offset,
            String unit,
            List<Integer> validSsm) {}

    private final String version;
    private final Map<String, Entry> entries;

    public Dictionary(String version, Map<String, Entry> entries) {
        this.version = version;
        this.entries = entries;
    }

    public String version() {
        return version;
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public Entry lookup(String labelOctal) {
        return entries.get(labelOctal);
    }

    public static Dictionary fromJson(JsonNode node) {
        String version = node.get("version").asText();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (JsonNode e : node.withArray("entries")) {
            List<Integer> validSsm = new ArrayList<>();
            for (JsonNode s : e.withArray("validSsm")) {
                validSsm.add(s.asInt());
            }
            Entry entry = new Entry(
                    e.get("label").asText(),
                    e.get("mnemonic").asText(),
                    e.get("type").asText(),
                    e.path("scale").asDouble(1.0),
                    e.path("offset").asDouble(0.0),
                    e.path("unit").asText(""),
                    validSsm);
            entries.put(entry.labelOctal(), entry);
        }
        return new Dictionary(version, entries);
    }

    /** Fingerprints one entry so dependency invalidation can compare versions field by field. */
    public static String entryFingerprint(Entry e) {
        if (e == null) {
            return "<absent>";
        }
        return e.mnemonic() + "|" + e.type() + "|" + e.scale() + "|" + e.offset()
                + "|" + e.unit() + "|" + e.validSsm();
    }
}
