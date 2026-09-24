package a429.interpret;

import java.util.List;

/**
 * Aggregated engineering value for a channel+label. Only parity-good, SSM-normal, convertible
 * words are members; every excluded word is retained with its diagnostic rather than dropped.
 */
public record Aggregate(String uid, String channel, String labelOctal, String unit,
                        double mean, int memberCount, List<String> memberWordUids,
                        List<Exclusion> exclusions, String dictVersion) {

    public record Exclusion(String wordUid, String reason) {
    }
}
