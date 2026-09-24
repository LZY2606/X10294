package a429.domain;

/**
 * One imported observation. Source and interpretation are kept separate: this
 * record only stores what was captured. position is the in-place sequence
 * index (0-based) inside the imported fragment.
 */
public record RawWord(
        String wordId,
        String channelId,
        int position,
        long receivedAtNs,
        String receivedAtIso,
        String wireHex,
        String fragmentId) {
}
