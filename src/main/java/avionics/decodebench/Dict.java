package avionics.decodebench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned label dictionary. The octal display of a label never changes
 * the underlying bits; BNR/BCD/discrete interpretation is decided entirely
 * by the dictionary version that a branch pins.
 */
public final class Dict {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Encoding { BNR, BCD, DISCRETE }

    public record Entry(
            String labelOct,
            String name,
            Encoding encoding,
            double resolution,
            double offset,
            String unit,
            String basis) {

        public static Entry fromJson(JsonNode n) {
            return new Entry(
                    n.get("label").asText(),
                    n.get("name").asText(),
                    Encoding.valueOf(n.get("encoding").asText()),
                    n.has("resolution") ? n.get("resolution").asDouble() : 1.0,
                    n.has("offset") ? n.get("offset").asDouble() : 0.0,
                    n.has("unit") ? n.get("unit").asText() : "",
                    n.has("basis") ? n.get("basis").asText() : "");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", labelOct);
            m.put("name", name);
            m.put("encoding", encoding.name());
            m.put("resolution", resolution);
            m.put("offset", offset);
            m.put("unit", unit);
            m.put("basis", basis);
            return m;
        }
    }

    public record LabelDict(String version, Map<String, Entry> entries) {

        public Entry find(String labelOct) {
            return entries.get(labelOct);
        }

        public static LabelDict fromJson(String json) {
            try {
                JsonNode root = MAPPER.readTree(json);
                Map<String, Entry> entries = new LinkedHashMap<>();
                for (JsonNode e : root.get("entries")) {
                    Entry entry = Entry.fromJson(e);
                    entries.put(entry.labelOct(), entry);
                }
                return new LabelDict(root.get("version").asText(), entries);
            } catch (Exception e) {
                throw new IllegalArgumentException("bad dictionary json: " + e.getMessage(), e);
            }
        }

        public String toJson() {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", version);
                m.put("entries", entries.values().stream().map(Entry::toMap).toList());
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Labels whose entry differs from the other version (invalidation scope). */
        public java.util.Set<String> diffLabels(LabelDict other) {
            java.util.Set<String> out = new java.util.TreeSet<>();
            for (String k : entries.keySet()) {
                Entry a = entries.get(k);
                Entry b = other.entries.get(k);
                if (b == null || !a.toMap().equals(b.toMap())) out.add(k);
            }
            for (String k : other.entries.keySet()) {
                if (!entries.containsKey(k)) out.add(k);
            }
            return out;
        }
    }

    private Dict() {}
}
