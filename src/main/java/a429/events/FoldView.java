package a429.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** A read-only snapshot of one branch at one sequence. */
public record FoldView(
        int seq,
        String lastEventId,
        double gapFactor,
        double switchRatio,
        java.util.Map<String, String> dictByChannel,
        JsonNode words,
        JsonNode engineering,
        JsonNode aggregates,
        JsonNode rates,
        List<Event> events,
        JsonNode decisions,
        JsonNode sourcePatches) {
}
