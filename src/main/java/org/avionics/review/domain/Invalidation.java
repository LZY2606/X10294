package org.avionics.review.domain;

public record Invalidation(String eventId, String scopeType, String sid, String reason) {
}
