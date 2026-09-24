package a429.event;

import java.util.List;

/**
 * Machine-readable blast radius: when a parameter or reference version changes,
 * only objects whose dependency key appears here are stale. Conclusions outside
 * this scope keep their original basis and are not globally replaced.
 */
public record Invalidation(
        String reason,
        String causedByEventId,
        List<String> dependencyKeys,
        List<String> invalidatedAnalysisIds,
        List<String> invalidatedRulingIds,
        String scope
) {
}
