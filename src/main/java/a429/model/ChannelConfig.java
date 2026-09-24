package a429.model;

import java.util.List;

/** Imported channel configuration (documentation + expected labels), kept as source data. */
public record ChannelConfig(String channel, String bus, List<String> expectedLabels) {
}
