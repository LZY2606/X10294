package org.avionics.review.domain;

import java.util.List;
import java.util.Map;

/**
 * A single interpretation of one word. Every value carries the dictionary
 * version and decoding convention that produced it, plus a stable identity so
 * the UI can track it across replays.
 */
public record EngineeringValue(
        String sid,
        String wordSid,
        String convention,
        String dictVersion,
        String labelOctal,
        String name,
        String encoding,
        String valueText,
        Double numeric,
        String unit,
        String coordinateBasis,
        String status,
        List<String> diagnostics,
        Map<String, String> discreteStates) {

    public boolean validForAggregate() {
        return "OK".equals(status) && numeric != null;
    }
}
