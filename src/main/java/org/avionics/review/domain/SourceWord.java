package org.avionics.review.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An imported raw observation. Nothing here is ever cleaned or rewritten: the capture is
 * kept exactly as imported; normalization lives only on the derived (candidate) layer.
 *
 * @param wordId     stable identity, kept across export/delete/reimport
 * @param ordinal    in-place sequence number (1-based, as captured)
 * @param receivedAt original time representation from the capture file
 * @param receivedNanos timestamp normalized to channel-relative nanoseconds (derived)
 * @param channel    channel id from the channel configuration
 * @param rawHex     original representation of the 32-bit word
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceWord(String wordId, int ordinal, String receivedAt, long receivedNanos,
                         String channel, String rawHex) {
    public int rawInt() {
        return (int) Long.parseLong(rawHex.replace("0x", "").replace("0X", ""), 16);
    }
}
