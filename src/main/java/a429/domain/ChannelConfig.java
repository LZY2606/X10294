package a429.domain;

import java.util.Map;

/** Channel configuration as imported; configuredSpeed is documentation, not a forced answer. */
public record ChannelConfig(String channelId, String name, String configuredSpeed,
                            Map<String, Object> notes) {
}
