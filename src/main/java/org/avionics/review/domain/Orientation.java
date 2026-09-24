package org.avionics.review.domain;

/** Which bit order was used when the 32 bits were sampled from the wire. */
public enum Orientation {
    /** ARINC standard: bit 1 first (label LSB-first). */
    NORMAL,
    /** The capture was sampled MSB-first; the raw word must be bit-reversed before decoding. */
    REVERSED
}
