package org.avionics.review.domain;

public record ChannelConfig(String channel, String name, double expectedRateKhz, String bus) {
}
