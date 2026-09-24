package av;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned label dictionary. BNR/BCD/discrete interpretation is governed by
 * the dictionary version pinned to each interpretation branch
 * (rule: dict-version-governs-encoding).
 */
public final class Dictionary {

    public static final class Entry {
        public final String labelOctal;
        public final String name;
        public final String encoding;
        public final int sigBits;
        public final double scale;
        public final double offset;
        public final String unit;

        Entry(String labelOctal, String name, String encoding, int sigBits,
              double scale, double offset, String unit) {
            this.labelOctal = labelOctal;
            this.name = name;
            this.encoding = encoding;
            this.sigBits = sigBits;
            this.scale = scale;
            this.offset = offset;
            this.unit = unit;
        }
    }

    public final String version;
    public final Map<String, Entry> entries = new LinkedHashMap<>();

    private Dictionary(String version) {
        this.version = version;
    }

    public Entry find(String labelOctal) {
        return entries.get(labelOctal);
    }

    public static Dictionary fromJson(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            Dictionary d = new Dictionary(root.get("version").asText());
            for (JsonNode e : root.get("entries")) {
                Entry en = new Entry(
                        e.get("label").asText(),
                        e.get("name").asText(),
                        e.get("encoding").asText(),
                        e.has("sigBits") ? e.get("sigBits").asInt() : 19,
                        e.has("scale") ? e.get("scale").asDouble() : 1.0,
                        e.has("offset") ? e.get("offset").asDouble() : 0.0,
                        e.has("unit") ? e.get("unit").asText() : "");
                d.entries.put(en.labelOctal, en);
            }
            return d;
        } catch (Exception ex) {
            throw new IllegalArgumentException("bad dictionary json: " + ex.getMessage(), ex);
        }
    }
}
