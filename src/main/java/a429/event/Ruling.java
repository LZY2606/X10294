package a429.event;

import java.util.List;

/** A human adjudication. Rejected candidates and rejected expectations are kept. */
public record Ruling(
        String rulingId,
        String eventId,
        long seq,
        String scope,
        String targetId,
        String decision,
        String chosenCandidate,
        List<String> retainedCandidates,
        List<String> rejectedCandidates,
        String note,
        String basedOnAnalysisId,
        List<String> dependencyKeys,
        boolean stale,
        String staleReason,
        String revokedByEventId
) {
}
