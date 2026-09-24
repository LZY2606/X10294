package a429.decode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An immutable named version of the label dictionary. */
public final class Dictionary {
    private final String version;
    private final String description;
    private final Map<String, LabelDef> labels;

    public Dictionary(String version, String description, Map<String, LabelDef> labels) {
        this.version = version;
        this.description = description;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    public String version() { return version; }
    public String description() { return description; }
    public Map<String, LabelDef> labels() { return labels; }

    public LabelDef lookup(String labelOctal) {
        return labels.getOrDefault(labelOctal, LabelDef.none(labelOctal));
    }
}
