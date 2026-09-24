package a429.ids;

import a429.rate.RateSegment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Stable, content-derived identities for every displayed value. UIDs are pure functions of their
 * inputs, so export -> delete database -> import regenerates identical identities, order and
 * provenance links.
 */
public final class Ids {
    private Ids() {}

    public static String of(String prefix, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return prefix + "_" + hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String wordUid(int seq, long tsNanos, String channel, int word) {
        return of("w", join(String.valueOf(seq), String.valueOf(tsNanos), channel,
                String.format("%08x", word)));
    }

    public static String fieldUid(String wordUid) {
        return of("f", wordUid);
    }

    public static String labelOctalUid(String wordUid) {
        return of("lo", wordUid);
    }

    public static String engUid(String wordUid, String dictId, String dictVersion) {
        return of("e", join(wordUid, dictId, dictVersion));
    }

    public static String intervalUid(String channel, int leftSeq, int rightSeq) {
        return of("i", join(channel, String.valueOf(leftSeq), String.valueOf(rightSeq)));
    }

    public static String rateCandidate(String channel, String paramsVersion,
                                       List<RateSegment> segments, List<Integer> bridgedGapAfterSeq) {
        StringBuilder content = new StringBuilder(channel).append("|").append(paramsVersion);
        for (RateSegment segment : segments) {
            content.append("|s:").append(segment.rateBps())
                    .append(":").append(segment.startSeq())
                    .append("-").append(segment.endSeq());
        }
        for (Integer seq : bridgedGapAfterSeq) {
            content.append("|b:").append(seq);
        }
        return of("rc", content.toString());
    }

    public static String rateScopeUid(String channel, String paramsVersion) {
        return of("rs", join(channel, paramsVersion));
    }

    public static String aggregateUid(String channel, String labelOctal, String dictId,
                                      List<String> memberWordUids) {
        return of("ag", join(channel, labelOctal, dictId, String.join(",", memberWordUids)));
    }

    public static String join(String... parts) {
        return String.join("\u001f", parts);
    }
}
