package org.avionics.review.domain;

import java.util.List;

/**
 * The candidates derived for one word at one point in time, plus the human selection.
 *
 * @param adoptedId candidate id currently accepted; null means no ruling (kept ambiguous)
 */
public record DecodeView(String wordId, List<WordCandidate> candidates, String adoptedId,
                         String autoAdoptedId) {

    public WordCandidate adopted() {
        String id = adoptedId != null ? adoptedId : autoAdoptedId;
        if (id == null) {
            return null;
        }
        return candidates.stream().filter(c -> c.candidateId().equals(id)).findFirst().orElse(null);
    }

    public WordCandidate byId(String id) {
        return candidates.stream().filter(c -> c.candidateId().equals(id)).findFirst().orElse(null);
    }
}
