package org.avionics.review.store;

import com.fasterxml.jackson.databind.JsonNode;
import org.avionics.review.domain.ChannelConfig;
import org.avionics.review.domain.DictDef;
import org.avionics.review.domain.DictVersion;
import org.avionics.review.domain.RawWord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materialized state obtained by replaying the events of one branch.
 *
 * <p>Source data is append-only: a SOURCE_PATCH replaces the active copy of a
 * word but keeps the origin chain, and the pre-patch byte remains in the
 * originating event payload for historical replay.</p>
 */
public class State {

    public record Decision(String eventId, String targetSid, String action,
                           String chosenSid, JsonNode params, String note,
                           String ruleVersion, String revokedBy) {
    }

    public String headEventId = null;
    public String importId = null;
    public final Map<String, ChannelConfig> channels = new LinkedHashMap<>();
    public final Map<String, DictVersion> dictById = new LinkedHashMap<>();
    public final Map<String, List<DictVersion>> dictByLabel = new LinkedHashMap<>();
    public final Map<String, RawWord> wordByKey = new LinkedHashMap<>();
    public final List<Decision> decisions = new ArrayList<>();
    public double gapThresholdMs = 3.0d;
    public double rateSwitchRatio = 0.25d;
    public final List<String> appliedEventIds = new ArrayList<>();

    public List<RawWord> activeWords() {
        return new ArrayList<>(wordByKey.values());
    }

    public void addDictionary(DictVersion dv) {
        dictById.put(dv.versionId(), dv);
        for (DictDef def : dv.defs()) {
            dictByLabel.computeIfAbsent(def.labelOctal(), k -> new ArrayList<>())
                    .removeIf(existing -> existing.versionId().equals(dv.versionId()));
            dictByLabel.computeIfAbsent(def.labelOctal(), k -> new ArrayList<>()).add(dv);
        }
    }

    public void putWord(RawWord word) {
        wordByKey.put(word.importId() + "#" + word.seq(), word);
    }

    public RawWord word(String importId, long seq) {
        return wordByKey.get(importId + "#" + seq);
    }
}
