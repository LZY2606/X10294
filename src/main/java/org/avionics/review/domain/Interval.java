package org.avionics.review.domain;

/** Gap between two consecutive received words on one channel. */
public record Interval(
        String sid,
        String channel,
        int indexInChannel,
        String fromWordSid,
        String toWordSid,
        double deltaMs,
        boolean acquisitionGap) {

    public double rateKhz() {
        return deltaMs <= 0 ? 0 : 1.0d / deltaMs;
    }
}
