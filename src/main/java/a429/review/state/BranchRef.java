package a429.review.state;

/** Branch registry entry. */
public record BranchRef(String branchId,
                        String materialId,
                        String dictionaryVersion,
                        long createdAtSeq,
                        String reason) {
}
