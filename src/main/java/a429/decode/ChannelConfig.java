package a429.decode;

import java.util.Set;

/**
 * Channel configuration.
 *
 * @param labelBitOrder "STD"  -> label bits 8..1 are LSB-first (ARINC 429 standard)
 *                      "MSB"  -> label bits 8..1 are interpreted MSB-first
 * @param nominalRateBps configured link rate in bits per second (12500 or 100000)
 */
public record ChannelConfig(String channelId, String labelBitOrder, int nominalRateBps,
                            String busName, String coordinateBasis) {

    public static final Set<Integer> LEGAL_RATES = Set.of(12500, 100000);

    public boolean standardLabelOrder() {
        return !"MSB".equalsIgnoreCase(labelBitOrder);
    }
}
