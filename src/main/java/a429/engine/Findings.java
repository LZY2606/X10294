package a429.engine;

import a429.rate.RateCandidate;

import java.util.List;

/** Every review-screen value: each carries a stable identity and provenance node kind. */
public final class Findings {
    private Findings() {}

    public record WordFinding(String wordUid, String fieldUid, String labelOctalUid, int seq,
                              long tsNanos, String channel, String rawHex, boolean parityOk,
                              boolean reversedCapture, int labelFieldHex, String labelOctal,
                              int labelNumber, int sdi, int ssmBits, String ssmName,
                              int dataHex, String dictVersion) {
    }

    public record EngFinding(String uid, String wordUid, int seq, String channel,
                             String labelOctal, boolean valid, double value, String display,
                             String unit, String diagnosis, String dictVersion,
                             String dictEntryUid) {
    }

    public record IntervalFinding(String uid, String channel, int leftSeq, int rightSeq,
                                  long gapNanos, List<Integer> compatibleRates,
                                  boolean acquisitionGap, boolean leftParityBad,
                                  boolean rightParityBad, String paramsVersion) {
    }

    public record RateScopeFinding(String uid, String channel, String paramsVersion,
                                   List<RateCandidate> candidates,
                                   Snapshot.Decision decision) {
    }

    public record AggregateFinding(String uid, String channel, String labelOctal, String unit,
                                   double mean, int memberCount, List<String> memberWordUids,
                                   List<AggregateFinding.Exclusion> exclusions,
                                   String dictVersion) {
        public record Exclusion(String wordUid, int seq, String reason) {
        }
    }
}
