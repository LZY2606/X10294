package a429.casepkg;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Parses receive-time text at nanosecond resolution. An unparseable original
 * representation is kept verbatim and maps to a null epoch: derivation skips
 * timing for it instead of dropping or rewriting it.
 */
public final class TimeStamp {
    private TimeStamp() {
    }

    public static Long parseEpochNanos(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            Instant instant = Instant.parse(t);
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                    instant.getNano());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
