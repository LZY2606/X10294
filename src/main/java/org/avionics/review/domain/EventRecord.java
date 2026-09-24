package org.avionics.review.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record EventRecord(
        String eventId,
        long seq,
        String branch,
        String type,
        JsonNode payload,
        String ruleVersion,
        String createdAt,
        String supersededBy) {
}
