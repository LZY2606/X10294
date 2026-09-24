package a429.events;

public record BranchInfo(String branchId, String parentBranchId, String name, int forkedAtSeq) {
}
