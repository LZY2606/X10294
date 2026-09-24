package a429.event;

import java.util.List;

/** A derived observation captured as an event; candidates are retained in full. */
public record Analysis(
        String analysisId,
        String eventId,
        long seq,
        String kind,
        String targetId,
        List<String> candidates,
        boolean unique,
        List<String> dependencyKeys,
        String basisSummary,
        boolean stale,
        String staleReason
) {
}
