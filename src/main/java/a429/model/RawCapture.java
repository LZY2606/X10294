package a429.model;

/**
 * A captured 32-bit word exactly as imported. seq is the in-place (original) order; tsNanos is
 * the receive timestamp in nanoseconds. No field is normalized here.
 */
public record RawCapture(int seq, long tsNanos, String channel, int word) {
}
