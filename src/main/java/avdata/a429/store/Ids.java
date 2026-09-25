package avdata.a429.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic stable identities so export/re-import preserves order and links. */
public final class Ids {
    private Ids() {}

    public static String hash(String prefix, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String wordId(String batchId, long seq) {
        return hash("w", batchId + "#" + seq);
    }

    public static String artifactIdentity(String kind, String targetRef, String salt) {
        return hash("a", kind + "|" + targetRef + "|" + salt);
    }
}
