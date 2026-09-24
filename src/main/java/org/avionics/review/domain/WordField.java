package org.avionics.review.domain;

/**
 * Field-level decomposition of a 32-bit word. Each line is a stable display
 * row in the UI.
 */
public record WordField(String sid, String field, int msb, int lsb, String binary,
                        String rawOctalOrHex, String note) {
}
