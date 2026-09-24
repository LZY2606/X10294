package a429.event;

import java.util.List;

/** One replayed single-source mutation with adopted and rejected outcomes both retained. */
public record CounterexampleResult(
        String mutationId,
        String title,
        String alters,
        String directSourceWordId,
        List<Outcome> outcomes,
        List<UnchangedObject> unchangedObjects,
        String baselineRef,
        String eventId
) {
    public record Outcome(String check, boolean expectedAccepted, boolean observed,
                          boolean adopted, Object observedValue, Object expectedValue,
                          String rationale) {
    }

    public record UnchangedObject(String objectId, String aspect, Object value) {
    }
}
