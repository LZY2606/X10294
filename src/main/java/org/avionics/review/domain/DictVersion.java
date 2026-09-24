package org.avionics.review.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable version of the label dictionary. A revision creates a new
 * {@code DictVersion}; old versions stay readable forever and old engineering
 * values keep citing the version that produced them.
 */
public record DictVersion(
        String versionId,
        String publishedAt,
        String unitSign,
        String coordinateBasis,
        List<DictDef> defs) {

    public Map<String, DictDef> defByLabel() {
        Map<String, DictDef> map = new LinkedHashMap<>();
        for (DictDef def : defs) {
            map.put(def.labelOctal(), def);
        }
        return map;
    }

    public DictDef defFor(String labelOctal) {
        return defByLabel().get(labelOctal);
    }
}
