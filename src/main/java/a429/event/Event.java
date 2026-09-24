package a429.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Append-only, machine-readable event.
 * The {@code seq} is per-branch (stable across export/delete/reimport);
 * {@code payload} is JSON so old versions can always be read back.
 */
public record Event(
        String eventId,
        String caseId,
        String branchId,
        long seq,
        String type,
        long createdEpochMs,
        String actor,
        JsonNode payload,
        JsonNode basis
) {
}
