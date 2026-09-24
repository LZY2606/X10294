package org.avionics.review.domain;

import java.util.List;

/** A run of words between acquisition gaps, with every legal rate candidate retained. */
public record RateSegment(String channel, int index, String startWordId, String endWordId,
                          List<String> wordIds, List<RateCandidate> candidates,
                          String ambiguity) {
}
