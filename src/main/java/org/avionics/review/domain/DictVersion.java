package org.avionics.review.domain;

import java.util.List;

/** A named, immutable revision of the label dictionary. */
public record DictVersion(String version, String note, List<LabelDef> labels) {

    public LabelDef lookup(String octalLabel, int sdi) {
        LabelDef wildcard = null;
        for (LabelDef def : labels) {
            if (!def.label().equals(octalLabel)) {
                continue;
            }
            if (def.sdi() == null) {
                wildcard = def;
            } else if (def.sdi() == sdi) {
                return def;
            }
        }
        return wildcard;
    }
}
