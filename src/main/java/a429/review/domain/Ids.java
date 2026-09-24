package a429.review.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic stable identities. Identities are derived only from immutable
 * content (material id + in-place ordinal + raw word; event inputs; target
 * objects), so they survive export -> delete local database -> re-import and
 * every replay produces the same ids.
 */
public final class Ids {

    private Ids() {
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortHash(String s) {
        return sha256Hex(s).substring(0, 16);
    }

    public static String materialId(String caseId) {
        return "mat-" + shortHash("material:" + caseId);
    }

    /** Raw word identity: material + original in-place sequence + raw 32 bits. */
    public static String wordUid(String materialId, int ordinal, long word) {
        return "word-" + shortHash(materialId + "|" + ordinal + "|" + Arinc429.hex(word));
    }

    /** A patched raw word is a distinct source revision, never overwriting the original. */
    public static String patchedWordUid(String materialId, int ordinal, long word, int patchSeq) {
        return "pword-" + shortHash(materialId + "|" + ordinal + "|" + Arinc429.hex(word) + "|r" + patchSeq);
    }

    public static String interpUid(String wordUid, String dictionaryVersion) {
        return "interp-" + shortHash(wordUid + "|" + dictionaryVersion + "|" + Arinc429.DECODER_VERSION);
    }

    public static String candidateUid(String interpUid, int candidateIndex) {
        return "cand-" + shortHash(interpUid + "|" + candidateIndex);
    }

    public static String streamUid(String materialId, String channel, String octalLabel) {
        return "stream-" + shortHash(materialId + "|" + channel + "|" + octalLabel);
    }

    public static String segmentUid(String streamUid, int segmentIndex, String effectiveParamsId) {
        return "seg-" + shortHash(streamUid + "|" + segmentIndex + "|" + effectiveParamsId);
    }

    public static String eventId(long seq, String type, String branchId, String inputsHash) {
        return "evt-" + shortHash(seq + "|" + type + "|" + branchId + "|" + inputsHash);
    }

    public static String branchId(String materialId, String dictionaryVersion, int forkSeq) {
        return "br-" + shortHash(materialId + "|" + dictionaryVersion + "|fork" + forkSeq);
    }
}
