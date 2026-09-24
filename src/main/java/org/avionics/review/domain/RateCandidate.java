package org.avionics.review.domain;

import java.util.List;

/** One legal segmentation hypothesis for a channel. All are kept. */
public record RateCandidate(
        String sid,
        String channel,
        String method,
        String rationale,
        boolean preferred,
        List<RateSegment> segments) {
}
