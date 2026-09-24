package org.avionics.review.domain;

public record AggregateValue(
        String sid,
        String label,
        String dictVersion,
        String convention,
        String name,
        String unit,
        int wordCount,
        int excludedBadParity,
        int excludedSsmInvalid,
        int excludedBcdIllegal,
        double mean) {
}
