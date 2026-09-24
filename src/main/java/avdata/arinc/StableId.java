package avdata.arinc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic stable identities so export/delete/reimport preserves identity, order and
 *  provenance links. Ids are content-addressed from the case id and stable source keys. */
public final class StableId {

    private StableId() {
    }

    public static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((byte) 0x1E);
                byte[] bytes = (p == null ? "" : p).getBytes(StandardCharsets.UTF_8);
                md.update(bytes);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(String kind, String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = kind;
        System.arraycopy(parts, 0, all, 1, parts.length);
        return kind + "_" + hash(all);
    }
}
