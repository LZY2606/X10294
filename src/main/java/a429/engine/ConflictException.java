package a429.engine;

/** Raised when two review windows save against the same branch head; second commit loses. */
public class ConflictException extends RuntimeException {
    private final String branchId;
    private final int expectedSeq;
    private final int actualSeq;

    public ConflictException(String branchId, int expectedSeq, int actualSeq) {
        super("version conflict on " + branchId + ": expected head " + expectedSeq
                + " but current head is " + actualSeq);
        this.branchId = branchId;
        this.expectedSeq = expectedSeq;
        this.actualSeq = actualSeq;
    }

    public String branchId() {
        return branchId;
    }

    public int expectedSeq() {
        return expectedSeq;
    }

    public int actualSeq() {
        return actualSeq;
    }
}
