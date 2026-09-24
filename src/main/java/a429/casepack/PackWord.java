package a429.casepack;

/** Raw captured row exactly as authored in the offline case pack. */
public record PackWord(
        String wordId,
        String channelId,
        int position,
        long receivedAtNs,
        String receivedAtIso,
        String wireHex,
        String fragmentId,
        String note) {
}
