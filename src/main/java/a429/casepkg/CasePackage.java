package a429.casepkg;

import java.util.List;
import java.util.Map;

/**
 * The shipped offline case package
 * "字路译码台：ARINC 429 标签、奇偶与速率分段证据".
 *
 * Channels carry wire-speed configuration; dictionary revisions carry
 * parameter changes; words carry the original receive-text representation.
 */
public record CasePackage(
        String id,
        String title,
        String basis,
        String rulesVersion,
        List<ChannelSpec> channels,
        List<DictionarySpec> dictionaries,
        List<WordSpec> words,
        List<MutationSpec> mutations,
        RateParamsSpec initialParams
) {
    public record ChannelSpec(String id, String name, String configuredSpeed,
                              Map<String, Object> notes) {
    }

    public record DictionarySpec(String id, String version, boolean activate,
                                 String path, Map<String, Object> changeNote) {
    }

    public record WordSpec(String id, long seq, String receiveText, String channelId,
                           String rawHex, String origin) {
    }

    public record MutationSpec(String id, String title, String alters,
                               String wordId, List<ExpectationSpec> expectations,
                               List<ExpectedObjectSpec> unaffectedObjects) {
    }

    public record ExpectationSpec(String check, boolean accepted, Object expected,
                                  String rationale) {
    }

    public record ExpectedObjectSpec(String objectId, String aspect, Object value) {
    }

    public record RateParamsSpec(double gapThresholdMs, double toleranceFraction,
                                 String labelConvention) {
    }
}
