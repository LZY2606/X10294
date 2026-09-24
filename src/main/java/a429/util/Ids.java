package a429.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 稳定身份：由自然键决定的确定性哈希。同一自然键在导出/删除/重新导入后仍得到同一 ID。
 */
public final class Ids {
    private Ids() {
    }

    public static String det(String prefix, Object... parts) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (Object p : parts) {
            byte[] b = String.valueOf(p).getBytes(StandardCharsets.UTF_8);
            md.update((byte) (b.length & 0xff));
            md.update((byte) ((b.length >> 8) & 0xff));
            md.update(b);
            md.update((byte) '|');
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 7; i++) {
            sb.append(String.format("%02x", d[i]));
        }
        return sb.toString();
    }
}
