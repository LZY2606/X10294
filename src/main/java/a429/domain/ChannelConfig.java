package a429.domain;

/**
 * Acquisition channel configuration. Captured words keep their exact wire bits;
 * {@code bitReversed} only describes how capture equipment ordered the 32 bits.
 */
public record ChannelConfig(
        String channelId,
        String name,
        boolean bitReversed,
        long nominalSpeedHz,
        String speedBasis) {

    public static ChannelConfig normal(String id, String name) {
        return new ChannelConfig(id, name, false, 100_000L, "ARINC 429 high-speed bus, 100 kHz");
    }
}
