package a429.review.state;

import java.util.List;
import java.util.Map;

/**
 * Immutable read-model snapshot of one branch at one point in the event log.
 * Historical snapshots are produced by replay only; a later parameter change
 * never overwrites an earlier snapshot.
 */
public record BranchState(String branchId,
                          String materialId,
                          String dictionaryVersion,
                          String decoderVersion,
                          Params params,
                          List<WordView> words,
                          List<StreamState> streams,
                          List<Decision> decisions,
                          List<InvalidatedRef> invalidated,
                          List<String> advisory) {

    public record Params(double tolerancePct, double gapFactor, List<Double> nominalRates,
                         long effectiveEventSeq, String revisionReason) {
    }

    public record Decision(String decisionUid,
                           String kind,
                           String segmentUid,
                           String candidateKey,
                           String wordUid,
                           long madeAtSeq,
                           long undoneAtSeq,
                           String actor,
                           String note,
                           long baseVersion,
                           boolean active) {
    }

    public record InvalidatedRef(String objectUid, String objectType, long atSeq, String reason) {
    }

    public WordView word(String uid) {
        for (WordView w : words) {
            if (w.wordUid().equals(uid)) {
                return w;
            }
        }
        return null;
    }

    public Decision activeDecisionFor(String target) {
        for (Decision d : decisions) {
            if (d.active() && target.equals(d.kind().equals("RATE") ? d.segmentUid() : d.wordUid())) {
                return d;
            }
        }
        return null;
    }

    public Map<String, Object> versionVector() {
        long latest = 0;
        for (Decision d : decisions) {
            latest = Math.max(latest, d.madeAtSeq());
        }
        return Map.of("branchId", branchId, "headSeq", latest == 0 ? 0L : latest);
    }
}
