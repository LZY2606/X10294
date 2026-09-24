package a429.rate;

/** Gap in nanoseconds between two consecutive received words of one key. */
public record Interval(
        String id,
        String channelId,
        String labelOctal,
        int fromPosition,
        int toPosition,
        long fromNs,
        long toNs,
        double gapMs) {
}
