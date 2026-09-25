package avdata.a429.decode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An immutable, versioned label dictionary. Lookup uses octal label + SDI. */
public final class LabelDictionary {
    public final String version;
    private final Map<String, List<DictionaryEntry>> byLabel = new LinkedHashMap<>();

    public LabelDictionary(String version, List<DictionaryEntry> entries) {
        this.version = version;
        for (DictionaryEntry e : entries) {
            byLabel.computeIfAbsent(e.labelOctal, k -> new ArrayList<>()).add(e);
        }
    }

    public String version() { return version; }

    /** All entries whose label matches; SDI disambiguation is applied by the caller. */
    public List<DictionaryEntry> candidates(String labelOctal, String sdi) {
        List<DictionaryEntry> out = new ArrayList<>();
        for (DictionaryEntry e : byLabel.getOrDefault(labelOctal, List.of())) {
            if (e.matches(labelOctal, sdi)) {
                out.add(e);
            }
        }
        return out;
    }
}
