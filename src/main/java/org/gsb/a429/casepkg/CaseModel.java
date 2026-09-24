package org.gsb.a429.casepkg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 案例包文件对应的 DTO（保留原始表示，导入时不做不可逆清洗）。 */
public final class CaseModel {
    private CaseModel() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Manifest {
        public String caseId;
        public String title;
        public String materialId;
        public int baselineRevision;
        public String baselineBranch;
        public String baselineDict;
        public List<Double> rateNominalHz;
        public double gapMultiple;
        public String description;
        public String baselineWordsFile;
        public String channelsFile;
        public String dictionariesFile;
        public String replayFile;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChannelFile {
        public String id;
        public String name;
        public String bitOrder;
        public String note;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WordFile {
        public String wordId;
        public int seq;
        public double tMs;
        public String channel;
        public String labelOctal;
        public int sdi;
        public int ssm;
        public String dataHex;
        public String word32Hex;
        public boolean reversed32;
        public String note;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dictionaries {
        public List<Version> versions;
        public List<Entry> entries;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Version {
        public String version;
        public String note;
        public String supersedes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public String sid;
        public String dictVersion;
        public String labelOctal;
        public Integer sdi;
        public String encoding;
        public String name;
        public Map<String, Object> spec;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Replay {
        public List<JsonNode> rationaleRules;
        public List<JsonNode> steps;
    }
}
