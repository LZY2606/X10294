package avionics.decode;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Label dictionary, versioned. Interpretation of BNR/BCD/DISC is decided by the dictionary version. */
public final class Dictionary {
    public record Entry(
            String labelOctal,
            String name,
            String type,            // BNR | BCD | DISC
            String unit,            // e.g. "ft", "kt", "" for discrete
            double resolution,
            boolean signed,
            String basis,           // coordinate/measurement basis, e.g. "altitude:baro-1013.25hPa"
            Set<Integer> ssmInvalid,
            Map<String, String> bits // DISC bit names: bit offset (0-18 within data field) -> name
    ) {}

    public final String id;
    public final String version;
    public final Map<String, Entry> entries;

    public Dictionary(String id, String version, Map<String, Entry> entries) {
        this.id = id;
        this.version = version;
        this.entries = entries;
    }

    public Entry get(String labelOctal) { return entries.get(labelOctal); }

    public static Dictionary fromJson(JsonNode n) {
        Map<String, Entry> map = new LinkedHashMap<>();
        JsonNode entries = n.get("entries");
        entries.fields().forEachRemaining(f -> {
            JsonNode e = f.getValue();
            Set<Integer> ssmInvalid = new TreeSet<>();
            if (e.has("ssmInvalid")) e.get("ssmInvalid").forEach(x -> ssmInvalid.add(x.asInt()));
            Map<String, String> bits = new LinkedHashMap<>();
            if (e.has("bits")) e.get("bits").fields().forEachRemaining(b -> bits.put(b.getKey(), b.getValue().asText()));
            map.put(f.getKey(), new Entry(
                    f.getKey(),
                    e.get("name").asText(),
                    e.get("type").asText(),
                    e.has("unit") ? e.get("unit").asText() : "",
                    e.has("resolution") ? e.get("resolution").asDouble() : 1.0,
                    e.has("signed") && e.get("signed").asBoolean(),
                    e.has("basis") ? e.get("basis").asText() : "",
                    ssmInvalid,
                    bits));
        });
        return new Dictionary(n.get("id").asText(), n.get("version").asText(), map);
    }

    /** True when the same label is defined differently across the two versions. */
    public boolean entryChanged(String labelOctal, Dictionary other) {
        Entry a = get(labelOctal), b = other.get(labelOctal);
        return !Objects.equals(a, b);
    }
}
