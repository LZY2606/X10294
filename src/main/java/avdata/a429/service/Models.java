package avdata.a429.service;

import java.util.List;

/** Plain data carriers exchanged between repository, service and web layer. */
public final class Models {
    private Models() {}

    public record RawWordIn(long seq, long receivedAtUs, String channel, int rawBits, String rawRepr) {}

    public record WordView(String wordId, String batchId, long seq, long receivedAtUs,
                           String channel, int rawBits, String rawRepr, String hex, String bits) {}

    public record EventView(String eventId, String branchId, long seq, String parentEventId,
                            String kind, long occurredAtUs, String actor, String payload,
                            String rationale, List<DepView> deps) {}

    public record DepView(String depType, String depRef) {}

    public record ArtifactView(String artifactId, String identity, String branchId,
                               String producedByEvent, String invalidatedByEvent,
                               String kind, String status, String targetRef, String payload) {}

    public record BranchView(String branchId, String parentBranchId, String name,
                             long createdAtUs, String tipEventId) {}

    public record ConflictResult(boolean conflict, String message, String currentTip,
                                 String submittedBase, List<String> diffEventIds) {}
}
