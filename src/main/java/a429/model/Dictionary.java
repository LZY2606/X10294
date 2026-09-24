package a429.model;

import java.util.Map;

/** Immutable dictionary revision. The version string decides BNR/BCD/discrete resolution. */
public record Dictionary(String dictId, String version, Map<String, LabelDef> defs) {
    public LabelDef get(String labelOctal) {
        return defs.get(labelOctal);
    }
}
