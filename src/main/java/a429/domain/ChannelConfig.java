package a429.domain;

/** 通道配置：标称链路速率（100kbit/s 高速 / 12-14.5kbit/s 低速）与采集缺口判定阈值。 */
public record ChannelConfig(
        String channel,
        String nominalSpeed,   // HIGH_100K | LOW_12_5K
        double gapThresholdMs // 相邻间隔超过该值视为采集缺口/速率边界
) {
    public static final double HIGH_WORD_INTERVAL_US = 320.0;
    public static final double LOW_WORD_INTERVAL_US = 1000.0; // 10 kword/s @100k, ~1.25 kword/s @12.5k

    public double nominalWordIntervalUs() {
        return "LOW_12_5K".equals(nominalSpeed) ? 800.0 : 320.0;
    }
}
