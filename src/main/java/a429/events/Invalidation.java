package a429.events;

import java.util.List;

/**
 * Machine-readable record of why derived entities were superseded and which
 * dependency edge caused it. This is the "judgement evidence" payload.
 */
public record Invalidation(
        String rule,
        String changedSource,
        List<String> affectedEntities,
        List<String> dependencyPath,
        String scope) {
}
