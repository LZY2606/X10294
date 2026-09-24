package avdata.arinc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Structure of the offline domain case pack
 *  ("字路译码台：ARINC 429 标签、奇偶与速率分段证据"). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CasePack {

    public String caseId;
    public String title;
    public String description;
    public List<DictVersionSpec> dictionaries;
    public List<ChannelSpec> channels;
    public MaterialSpec material;
    /** Replay script: parameter revision, local source patch, conflicting human decision. */
    public List<ScriptStep> script;
    /** Machine-readable evidence: accepted and rejected expected results with provenance. */
    public List<ExpectationSpec> expectations;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DictVersionSpec {
        public String version;
        public String ssmRules;          // CLASSIC / REVISED
        public String note;
        public List<LabelSpec> labels;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LabelSpec {
        public String label;
        public int sdi = -1;
        public String name;
        public String unit;
        public String coordBase = "";
        public String encoding;          // BNR/BCD/DISCRETE
        public int msb;
        public int lsb;
        public double resolution;
        public List<String> discrete = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ChannelSpec {
        public String channel;
        public double nominalKbps;
        public double scheduleSlotMs;
        public String polarity = "ODD";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MaterialSpec {
        public String id;
        public String dictionaryVersion;  // baseline dictionary version
        public List<WordSpec> words;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WordSpec {
        public int seq;
        public String received;          // original representation
        public long epochMs;
        public String channel;
        public String raw;               // 0x........ original representation
        public boolean reversed = false;
        public Boolean importParity;     // optional parity result carried by the source
        public String note;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ScriptStep {
        public String id;
        public String kind;              // PARAM_REVISION / SOURCE_PATCH / RULING / BRANCH / CONFLICT
        public String target;            // target object uid expression
        public String candidate;
        public boolean accepted;
        public String note;
        public Map<String, Object> params;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ExpectationSpec {
        public String id;
        public String feature;           // BIT_REVERSAL / PARITY / OCTAL_LABEL / ...
        public String objectUidExpr;     // e.g. "word:5"
        public String branch;            // branch name ("main")
        public String atEvent;           // event step id after which it applies
        public String field;             // asserted field
        public Object expected;
        public boolean adopted;          // accepted vs not-adopted result both retained
        public String rationale;
        public List<String> invalidates;  // uids whose conclusions must be invalidated by this
    }

    public static CasePack parse(String json) {
        try {
            return Json.MAPPER.readValue(json, CasePack.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid case pack: " + e.getMessage(), e);
        }
    }
}
