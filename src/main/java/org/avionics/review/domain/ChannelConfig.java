package org.avionics.review.domain;

import java.util.List;

/** Channel configuration imported with the capture. */
public record ChannelConfig(String id, String name, List<String> scheduledLabels,
                            Double nominalPeriodMicros) {
}
