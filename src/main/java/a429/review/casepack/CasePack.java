package a429.review.casepack;

import java.util.List;

/**
 * Offline counter-example archive: "字路译码台：ARINC 429 标签、奇偶与速率分段证据".
 * It is a self-contained domain bundle (baseline + replay steps), never a
 * generic spreadsheet.
 */
public final class CasePack {

    public static final class Pack {
        public String packId;
        public String title;
        public String timeBasis;
        public List<ChannelCfg> channels;
        public List<DictBlob> dictionaries;
        public List<WordInput> words;
        public List<Step> replay;
    }

    public static final class ChannelCfg {
        public String channel;
        public List<Double> nominalRates;
    }

    public static final class DictBlob {
        public String version;
        public List<Entry> entries;
    }

    public static final class Entry {
        public String label;
        public int sdi = -1;
        public String encoding;
        public String name;
        public String unit;
        public Double resolutionLsb;
        public Integer signBit;
        public Integer msb;
        public Integer lsb;
        public List<String> bits;
    }

    /** Original in-place record; both the raw timestamp representation and the
     *  normalized epoch nanoseconds are retained on import. */
    public static final class WordInput {
        public int ordinal;
        public String timestamp;
        public String channel;
        public String rawHex;
        public String note;
    }

    public static final class Step {
        public int no;
        public String kind;
        public String title;
        public String actor;
        public Payload payload;
        public Expect expect;
    }

    public static final class Payload {
        public int ordinal = -1;
        public String rawHex;
        public String reason;
        public String stream;
        public int segment = -1;
        public String candidate;
        public String wordUid;
        public String encoding;
        public long baseVersion;
        public Double tolerancePct;
        public Double gapFactor;
        public String dictionaryVersion;
        public String branchReason;
        public String undoDecisionUid;
    }

    public static final class Expect {
        public String assertion;
        public String affected;
        public String scope;
        public boolean adopted = true;
    }

    private CasePack() {
    }
}
