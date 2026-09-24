package a429.domain;

/**
 * An imported 32-bit word. Only original material lives here:
 * in-place sequence, received time (verbatim + parsed), channel and raw 32 bits.
 * Nothing is normalised or cleaned at import time.
 */
public record RawWord(
        String wordId,
        long seq,
        String receiveText,
        Long receiveEpochNanos,
        String channelId,
        long raw,
        String rawText
) {
}
