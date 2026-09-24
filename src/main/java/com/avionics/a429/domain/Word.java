package com.avionics.a429.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A received 32-bit ARINC 429 word together with its immutable provenance.
 * Raw representation (32 bits, sequence, receive time, channel) is kept
 * exactly as imported; derived decoding lives elsewhere.
 */
public final class Word {
    public final String id;
    public final String importId;
    public final long sequence;
    public final long receiveNanos;
    public final String channel;
    public final int raw;

    public Word(String id, String importId, long sequence, long receiveNanos,
                String channel, int raw) {
        this.id = id;
        this.importId = importId;
        this.sequence = sequence;
        this.receiveNanos = receiveNanos;
        this.channel = channel;
        this.raw = raw;
    }

    /** Parse a receive time given in decimal seconds since an arbitrary origin. */
    public static long parseSeconds(String text) {
        return new BigDecimal(text.trim())
                .setScale(9, RoundingMode.HALF_UP)
                .movePointRight(9)
                .longValueExact();
    }

    /** Render nanoseconds back to decimal seconds with up to 9 fraction digits. */
    public static String formatSeconds(long nanos) {
        boolean neg = nanos < 0;
        long abs = Math.abs(nanos);
        long whole = abs / 1_000_000_000L;
        long frac = abs % 1_000_000_000L;
        String s;
        if (frac == 0) {
            s = Long.toString(whole);
        } else {
            StringBuilder f = new StringBuilder(Long.toString(frac));
            while (f.length() < 9) {
                f.insert(0, '0');
            }
            while (f.charAt(f.length() - 1) == '0') {
                f.deleteCharAt(f.length() - 1);
            }
            s = whole + "." + f;
        }
        return neg ? "-" + s : s;
    }
}
