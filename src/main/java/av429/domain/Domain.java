package av429.domain;

import av429.codecs.Arinc429;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 来源层模型：这些记录只保存“导入时的原始表示”，派生解释永远不回写。
 */
public final class Domain {
    private Domain() {}

    /** 一条通道配置。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelConfig(
            String name,
            @JsonProperty("nominalKbps") double nominalKbps,
            @JsonProperty("nominalPeriodUs") long nominalPeriodUs) {
    }

    /** 标签字典条目；key 使用人工八进制 label（如 "310"）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DictEntry(
            String octal,
            String name,
            String encoding,          // BNR | BCD | DISCRETE
            @JsonProperty("signed") boolean signed,
            Double resolution,        // BNR: LSB 工程量
            @JsonProperty("decimalPlaces") Integer decimalPlaces, // BCD
            @JsonProperty("digitCount") Integer digitCount,       // BCD
            String unit,
            @JsonProperty("ssmFamily") String ssmFamily,          // BNR | BCD
            @JsonProperty("discreteMap") Map<String, String> discreteMap,
            @JsonProperty("coordinateBasis") String coordinateBasis) {
    }

    /** 某一版字典；条目集合的内容哈希决定解释身份，版本号仅作可读标签。 */
    public record TagDictionary(String version, Map<String, DictEntry> entries) {
        public TagDictionary {
            entries = entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
        }

        public DictEntry lookup(int word) {
            return entries.get(Arinc429.octalLabel(word, true));
        }
    }

    /** 原始字：原位序列与时间戳等全部保留。 */
    public record RawWord(
            long seq,
            long receiveTimeUs,
            String channel,
            int word) {
    }

    /** 导入清单（案例包或外部 CSV/JSON 的统一中间形态）。 */
    public record IngestBundle(
            Map<String, ChannelConfig> channels,
            List<TagDictionary> dictionaries,
            @JsonProperty("activeDictionaryVersion") String activeDictionaryVersion,
            Params params,
            List<RawWord> words) {
        public IngestBundle {
            channels = channels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(channels);
            dictionaries = dictionaries == null ? new ArrayList<>() : new ArrayList<>(dictionaries);
            words = words == null ? new ArrayList<>() : new ArrayList<>(words);
        }
    }

    /** 影响速率分段的可修订参数（单位明确标注）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Params(
            @JsonProperty("highMinGapUs") double highMinGapUs,
            @JsonProperty("lowMinGapUs") double lowMinGapUs,
            @JsonProperty("acqGapUs") double acqGapUs,
            @JsonProperty("rateToleranceRatio") double rateToleranceRatio,
            @JsonProperty("octalLabelReversed") boolean octalLabelReversed) {

        public static Params v1() {
            return new Params(200.0, 3_500.0, 50_000.0, 0.15, true);
        }

        public static Params fromJsonOrV1(JsonNode node) {
            if (node == null || node.isNull()) {
                return v1();
            }
            return new Params(
                    node.path("highMinGapUs").asDouble(200.0),
                    node.path("lowMinGapUs").asDouble(3_500.0),
                    node.path("acqGapUs").asDouble(50_000.0),
                    node.path("rateToleranceRatio").asDouble(0.15),
                    node.path("octalLabelReversed").asBoolean(true));
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("highMinGapUs", highMinGapUs);
            m.put("lowMinGapUs", lowMinGapUs);
            m.put("acqGapUs", acqGapUs);
            m.put("rateToleranceRatio", rateToleranceRatio);
            m.put("octalLabelReversed", octalLabelReversed);
            return m;
        }
    }
}
