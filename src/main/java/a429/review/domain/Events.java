package a429.review.domain;

/**
 * Append-only, machine-readable events. The event log is the source of truth;
 * events are never mutated or deleted. {@code branchScope} lists the branches
 * the event applies to (empty = baseline and every branch), which is how the
 * replay reconstructs historical conclusions without overwriting the past.
 */
public final class Events {

    public record Event(long seq,
                        String eventUid,
                        String type,
                        String branchId,
                        long atSeq,
                        String actor,
                        String inputsHash,
                        String detailJson) {
    }

    public static final String IMPORT = "MATERIAL_IMPORT";
    public static final String REINTERPRET = "REINTERPRET_FORK";
    public static final String SOURCE_PATCH = "SOURCE_PATCH";
    public static final String PARAMS_REVISED = "PARAMS_REVISED";
    public static final String RATE_DECIDED = "RATE_DECIDED";
    public static final String ENCODING_DECIDED = "ENCODING_DECIDED";
    public static final String VIEW = "VIEW_ACTION";
    public static final String DECISION_UNDONE = "DECISION_UNDONE";
    public static final String CONFLICT_REJECTED = "CONFLICT_REJECTED";
    public static final String REPLAY_STEP = "REPLAY_STEP";

    private Events() {
    }

    public static String hashInputs(String canonicalInputs) {
        return Ids.shortHash("inputs:" + canonicalInputs);
    }
}
