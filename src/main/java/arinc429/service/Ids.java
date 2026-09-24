package arinc429.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Deterministic stable identities used by every visible value. */
public final class Ids {

    private Ids() {
    }

    public static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    md.update((byte) 0x1f);
                }
                md.update(String.valueOf(parts[i]).getBytes(StandardCharsets.UTF_8));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decodedId(String branch, String material, String dict, String rule, String rawWordId) {
        return "dec-" + hash(branch, material, dict, rule, rawWordId);
    }

    public static String aggregateId(String branch, String channel, String label) {
        return "agg-" + hash(branch, channel, label);
    }

    public static String rateId(String branch, String channel) {
        return "rate-" + hash(branch, channel);
    }

    public static String eventId(String branch, int seq, String kind, String payloadHash) {
        return "ev-" + hash(branch, String.valueOf(seq), kind, payloadHash);
    }

    public static String branchId(String parent, String baseEvent, String material, String dict, String rule,
                                  String purpose) {
        return "br-" + hash(
                parent == null ? "" : parent,
                baseEvent == null ? "" : baseEvent,
                material, dict, rule, purpose);
    }
}
