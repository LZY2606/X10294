package avio.a429.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Label dictionary, versioned. BNR/BCD/discrete interpretation is fully
 * determined by the dictionary version pinned to an interpretation run.
 */
public final class Dictionary {
    public record Entry(String label, String name, String type,
                        double scale, double offset, String unit, String frame) {}

    public final String version;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Dictionary(String version) {
        this.version = version;
    }

    public static Dictionary fromJson(String version, JsonNode entriesNode) {
        Dictionary d = new Dictionary(version);
        for (JsonNode e : entriesNode) {
            Entry entry = new Entry(
                    e.get("label").asText(),
                    e.get("name").asText(),
                    e.get("type").asText(),
                    e.get("scale").asDouble(),
                    e.get("offset").asDouble(),
                    e.get("unit").asText(),
                    e.get("frame").asText());
            d.entries.put(entry.label(), entry);
        }
        return d;
    }

    public Optional<Entry> lookup(int labelOctal) {
        return Optional.ofNullable(entries.get(String.format("%03o", labelOctal)));
    }

    public Map<String, Entry> entries() {
        return entries;
    }
}
