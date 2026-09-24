package a429.events;

/** One immutable event on a branch timeline. */
public record Event(long id, String branchId, int seq, EventType type, String payloadJson,
                    String rationale, long createdAtNanos, String dependencyEdgesJson) {
}
