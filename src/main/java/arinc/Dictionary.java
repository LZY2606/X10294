package arinc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Label dictionary, versioned. Interpretation of BNR/BCD/discrete is fully
 * determined by (label, dictionary version); a version change invalidates
 * dependent interpretations through the dependency graph, never by silent
 * global replacement.
 */
public final class Dictionary {

    public record Entry(
            String labelOctal,
            String encoding,   // BNR | BCD | DISC
            String unit,       // e.g. "kt", "ft", "deg", "" for discrete
            double resolution, // BNR scale; 1.0 otherwise
            int sigBits,       // BNR significant bits incl. sign
            String description) {}

    private final String version;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Dictionary(String version) {
        this.version = version;
    }

    public String version() { return version; }

    public void put(Entry e) { entries.put(e.labelOctal(), e); }

    public boolean hasLabel(String labelOctal) { return entries.containsKey(labelOctal); }

    public Entry get(String labelOctal) { return entries.get(labelOctal); }

    public Map<String, Entry> entries() { return entries; }
}
