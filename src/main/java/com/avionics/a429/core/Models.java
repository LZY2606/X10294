package com.avionics.a429.core;

import java.util.List;
import java.util.Map;

/** 领域模型记录。所有派生产物都带稳定 uid。 */
public final class Models {
    private Models() {}

    /** 源数据项：导入时保留原始表示，解析失败留诊断，不做清洗。 */
    public record SourceItem(
            String itemId,
            String importId,
            int seq,
            String rxTime,        // 原始时间戳文本（ISO-8601）
            Long rxNanos,         // 解析后的纳秒时间，可空
            String channel,
            String rawText,       // 原始表示，如 "0x80C80C86"
            Long rawBits,         // 解析出的 32 位字，可空
            String parseDiag,     // 解析诊断，可空
            String sourceRef      // 来源引用，如 casepack:streams/ch1.csv#line4
    ) {}

    public record ChannelConfig(String name, ArincWord.BitOrder bitOrder, String description) {}

    /** 标签字典条目：BNR/BCD/DISCRETE 的工程值解释完全由字典版本决定。 */
    public record DictEntry(
            String entryId,
            String labelOctal,
            int sdi,              // -1 表示任意 SDI
            String dataType,      // BNR | BCD | DISCRETE
            String name,
            String unit,
            double resolution,
            Double rangeMax,
            Map<String, String> bitNames  // DISCRETE 用：位号 -> 名称
    ) {}

    public record LabelDict(String version, String description, List<DictEntry> entries) {
        public DictEntry find(String labelOctal, int sdi) {
            DictEntry anySdi = null;
            for (DictEntry e : entries) {
                if (!e.labelOctal().equals(labelOctal)) continue;
                if (e.sdi() == sdi) return e;
                if (e.sdi() == -1) anySdi = e;
            }
            return anySdi;
        }
    }

    /** 译码结果（派生层）。 */
    public record DecodedWord(
            String uid, String itemId, int seq, String rxTime, String channel,
            String rawHex, long rawBits, String bitOrder, long normalizedBits,
            boolean parityOk, String labelOctal, int labelBits, int sdi, int ssm,
            String ssmMeaning, String dataFieldHex,
            String dictVersion, boolean dictHit, String dictEntryId, String dataType,
            Double engValue, String engUnit, String engStatus, String diagnostic,
            boolean aggregateEligible, String excludeReason
    ) {}

    /** 相邻字间隔。 */
    public record GapInfo(
            String uid, String channel, int afterSeq, double dtMs,
            double bitsAtHigh, double bitsAtLow,
            List<String> candidates,   // HIGH / LOW / GAP / UNKNOWN 的合法候选全集
            String classification
    ) {}

    /** 速率分段候选，多候选全部保留。 */
    public record SegmentCandidate(
            String uid, String channel, int startSeq, int endSeq,
            String rate,               // HIGH | LOW | UNKNOWN
            List<String> alternatives,
            String status,             // CANDIDATE | ACCEPTED | REJECTED
            String adjudicatedBy       // 事件 uid，可空
    ) {}

    /** 工程值聚合：坏奇偶字与非法状态字不进入聚合值，但保留在排除清单中。 */
    public record Aggregate(
            String uid, String channel, String labelOctal, int sdi, String name,
            String unit, int count, Double min, Double max, Double mean,
            List<String> included,
            List<Map<String, String>> excluded   // uid -> reason
    ) {}
}
