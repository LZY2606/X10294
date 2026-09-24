package a429.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 领域模型：原始层与解释层分离。 */
public final class Model {
    private Model() {}

    /** 原始字：导入时保留原位序列、接收时间、通道、原始表示与字典版本。 */
    public record RawWord(String wordId, int seq, long rxMs, String channel,
                          String rawText, int rawInt, String rawBits) {}

    public record ChannelConfig(String channelId, String bitOrder, double nominalRateHz,
                                double tolPct, double gapFactor, String unit) {}

    public record DictEntry(String labelOctal, String mnemonic, String encoding,
                            String unit, Double resolution, Integer digits,
                            Integer decimalPlaces, Map<String, String> bitNames) {}

    /** 单字解释结果：每个显示值都带来源。 */
    public static final class WordView {
        public String wordId;
        public int seq;
        public long rxMs;
        public Long intervalMs;          // 与通道内前一字的间隔
        public String channel;
        public String rawText;           // 原始表示（导入时保留）
        public String rawHex;            // 规范化展示
        public boolean parityOk;
        public String labelOctal;        // 八进制展示，不改变底层位
        public int sdi;
        public int ssm;
        public String ssmMeaning;
        public boolean ssmValid;
        public String dataHex;
        public String encoding;          // 来自字典版本
        public String mnemonic;
        public String dictVersion;       // 字典命中来源
        public String bitOrder;          // 通道位序来源
        public Double engValue;          // 工程值；无法转换时为空
        public String engText;           // 工程值文本（含 discrete 位名）
        public String unit;              // 单位或坐标基准
        public List<String> diagnostics = new ArrayList<>();
        public String adjudication;      // ACCEPT / REJECT / null
        public boolean inAggregate;      // 是否进入聚合值
    }

    /** 速率分段候选：无法唯一决定时全部保留。 */
    public record Segment(int startSeq, int endSeq, int wordCount, double rateHz) {}

    public static final class RateCandidate {
        public String candidateId;
        public String channel;
        public String kind;              // SEG1 / SEG2 / SEG3
        public List<Segment> segments = new ArrayList<>();
        public List<Long> gapsMs = new ArrayList<>();
        public String adjudication;      // ACCEPT / REJECT / null
    }

    /** 聚合：坏奇偶字不进入聚合值。 */
    public static final class Aggregate {
        public String channel;
        public String labelOctal;
        public int total;
        public int used;
        public List<String> excluded = new ArrayList<>(); // wordId:原因
        public Double mean;
        public String unit;
    }
}
