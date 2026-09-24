package org.avionics.review.domain;

/**
 * A received 32-bit word in its original position. The raw hex is source data
 * and never mutated: a source patch appends a revision and keeps the original
 * byte readable through the event that introduced it.
 */
public record RawWord(
        String sid,
        String importId,
        long seq,
        double receiveTimeMs,
        String channel,
        String rawHex,
        String dictVersionAtImport,
        String revisionId,
        String originWordSid,
        String revisionReason) {

    public long raw() {
        return Long.parseLong(rawHex, 16);
    }

    public boolean isRevision() {
        return revisionId != null;
    }
}
