package avionics.arinc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标签字典（带版本）。BNR/BCD/discrete 的解释完全由字典版本决定。
 */
public final class Dictionary {
    public record Entry(String labelOctal, String mnemonic, String type, String unit,
                        double resolution, double offset, int digits, double scale,
                        double nominalIntervalMs, Map<String, String> bits) {}

    public final String id;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Dictionary(String id) {
        this.id = id;
    }

    public static Dictionary fromJson(JsonNode node) {
        Dictionary d = new Dictionary(node.get("id").asText());
        JsonNode labels = node.get("labels");
        labels.fields().forEachRemaining(e -> {
            JsonNode n = e.getValue();
            Map<String, String> bits = new LinkedHashMap<>();
            if (n.has("bits")) n.get("bits").fields().forEachRemaining(b -> bits.put(b.getKey(), b.getValue().asText()));
            d.entries.put(e.getKey(), new Entry(
                    e.getKey(),
                    n.get("mnemonic").asText(),
                    n.get("type").asText(),
                    n.has("unit") ? n.get("unit").asText() : "",
                    n.has("resolution") ? n.get("resolution").asDouble() : 1.0,
                    n.has("offset") ? n.get("offset").asDouble() : 0.0,
                    n.has("digits") ? n.get("digits").asInt() : 4,
                    n.has("scale") ? n.get("scale").asDouble() : 1.0,
                    n.has("nominalIntervalMs") ? n.get("nominalIntervalMs").asDouble() : 100.0,
                    bits));
        });
        return d;
    }

    public Entry lookup(String labelOctal) {
        return entries.get(labelOctal);
    }

    public Map<String, Entry> entries() {
        return entries;
    }
}
