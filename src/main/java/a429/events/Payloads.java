package a429.events;

import a429.model.ChannelConfig;
import a429.model.LabelDef;
import a429.model.RateParams;

import java.util.List;

/** Typed payloads for append-only events. */
public final class Payloads {
    private Payloads() {}

    public record ImportMaterial(List<ChannelConfig> channels, DictRef dictionary,
                                 ParamsRef params, List<ImportedWord> words, String sourceName) {
    }

    public record ImportedWord(int seq, long tsNanos, String channel, int word,
                               boolean reversedCapture) {
    }

    public record DictRef(String dictId, String version, List<LabelDef> defs) {
    }

    public record ParamsRef(String paramsId, String version, double toleranceRatio,
                            double gapFactor, int maxCandidates) {
        public RateParams toModel() {
            return new RateParams(paramsId, version, toleranceRatio, gapFactor, maxCandidates);
        }
    }

    public record SourceMutation(String mutationId, String kind, int seq, int oldWord,
                                 int newWord, Long newTsNanos, boolean reversedCapture,
                                 String note) {
    }

    public record Revision(String fromVersion, String toVersion, ParamsRef params) {
    }

    public record DictRevision(String fromVersion, DictRef dictionary) {
    }

    public record ForkRef(String parentBranchId, int fromSeq, String childBranchId, String label) {
    }

    public record Adjudication(String scopeUid, String decision, String targetCandidateUid,
                               int expectedHeadSeq) {
    }

    public record Invalidation(List<String> directNodes, List<String> impactedNodes,
                               String reason) {
    }
}
