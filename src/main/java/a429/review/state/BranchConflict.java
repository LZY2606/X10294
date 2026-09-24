package a429.review.state;

import java.util.ArrayList;
import java.util.List;

/** Builds the advisory state carried by a rejected (lost-update) decision. */
final class BranchConflict {

    private BranchConflict() {
    }

    static BranchState conflict(BranchState s, BranchState.Decision incoming, long lastSeq) {
        List<String> advisory = new ArrayList<>(s.advisory());
        advisory.add("CONFLICT_REJECTED: decision " + incoming.decisionUid()
                + " was based on version " + incoming.baseVersion()
                + " but the target was changed at event " + lastSeq
                + "; both versions are retained, nothing overwritten");
        return new BranchState(s.branchId(), s.materialId(), s.dictionaryVersion(), s.decoderVersion(),
                s.params(), s.words(), s.streams(), s.decisions(), s.invalidated(), List.copyOf(advisory));
    }
}
