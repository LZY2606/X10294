package a429.casepack;

/**
 * A prepared local source change used by the review narrative. Each patch only
 * touches one word revision, so the invalidation scope must stay local.
 */
public record SourcePatch(
        String patchId,
        String wordId,
        String wireHex,
        Long receivedAtNs,
        String receivedAtIso,
        long shiftMs,
        String changes,
        String expectedScope) {
}
