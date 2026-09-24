package avionics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Label dictionary, versioned. The dictionary version alone decides how
 * BNR / BCD / discrete data fields are interpreted.
 */
public final class Dictionary {
    public final String version;
    /** labelOctal ("203") -> entry */
    public final Map<String, Entry> entries = new LinkedHashMap<>();

    public Dictionary(String version) {
        this.version = version;
    }

    public record Entry(
            String labelOctal,
            String encoding,   // BNR | BCD | DISCRETE
            String name,       // e.g. ALTITUDE
            String unit,       // engineering unit, e.g. "ft"; "" for unitless
            double scale,      // LSB weight for BNR
            int dataBits,      // significant data bits (BNR two's complement width)
            String basis       // coordinate / reference basis note
    ) {}

    public Entry find(String labelOctal) {
        return entries.get(labelOctal);
    }

    public static Dictionary fromJson(String version, JsonNode node) {
        Dictionary d = new Dictionary(version);
        for (JsonNode e : node.withArray("entries")) {
            Entry entry = new Entry(
                    e.get("label").asText(),
                    e.get("encoding").asText(),
                    e.path("name").asText(""),
                    e.path("unit").asText(""),
                    e.path("scale").asDouble(1.0),
                    e.path("dataBits").asInt(19),
                    e.path("basis").asText(""));
            d.entries.put(entry.labelOctal(), entry);
        }
        return d;
    }

    /** Labels whose interpretation differs from another dictionary version. */
    public java.util.Set<String> changedLabels(Dictionary other) {
        java.util.Set<String> changed = new java.util.HashSet<>();
        java.util.Set<String> all = new java.util.HashSet<>(entries.keySet());
        all.addAll(other.entries.keySet());
        for (String label : all) {
            Entry a = entries.get(label);
            Entry b = other.entries.get(label);
            if (a == null || b == null || !a.equals(b)) changed.add(label);
        }
        return changed;
    }
}
