package a429.engine;

import a429.model.Dictionary;
import a429.model.RateParams;
import a429.model.RawCapture;

import java.util.Map;

/** Materialized view of a branch at one point in time, produced solely by replaying events. */
public record Snapshot(
        String branchId,
        int headSeq,
        Map<Integer, Snapshot.LiveWord> words,
        Dictionary dictionary,
        RateParams params,
        Map<String, Snapshot.Decision> adjudications) {

    public record LiveWord(RawCapture capture, boolean reversedCapture, String wordUid,
                           long firstEventId, Long supersededEventId) {
        public boolean live() {
            return supersededEventId == null;
        }
    }

    public record Decision(String scopeUid, String decision, String targetCandidateUid,
                           int eventSeq, boolean revoked) {
    }
}
