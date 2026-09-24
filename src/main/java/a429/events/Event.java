package a429.events;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One immutable event on a branch. Payload holds the machine-readable result;
 * depends lists dependency versions used; invalidates lists entities whose
 * derived conclusions were superseded by this event.
 */
public record Event(
        String eventId,
        String caseId,
        String branchId,
        int seq,
        String parentEventId,
        String type,
        JsonNode payload,
        JsonNode depends,
        JsonNode invalidates,
        String createdAt,
        int revision,
        String kind,
        JsonNode tags) {
}
