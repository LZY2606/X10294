package a429.event;

/** Discriminated event types used by the bench. */
public final class EventType {
    public static final String CASE_CREATED = "CaseCreated";
    public static final String SOURCE_BATCH_IMPORTED = "SourceBatchImported";
    public static final String DICTIONARY_REGISTERED = "DictionaryRegistered";
    public static final String PARAMETERS_REVISED = "ParametersRevised";
    public static final String DICT_ACTIVATED = "DictionaryActivated";
    public static final String ANALYSIS_OBSERVED = "AnalysisObserved";
    public static final String RULING_MADE = "RulingMade";
    public static final String RULING_REVOKED = "RulingRevoked";
    public static final String VIEW_PINNED = "ViewPinned";
    public static final String BRANCH_FORKED = "BranchForked";
    public static final String COUNTEREXAMPLE_REPLAYED = "CounterexampleReplayed";
    public static final String DEPENDENCIES_INVALIDATED = "DependenciesInvalidated";

    private EventType() {
    }
}
