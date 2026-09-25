package com.avionics.a429.core;

import com.avionics.a429.core.Models.ChannelConfig;
import com.avionics.a429.core.Models.DecodedWord;
import com.avionics.a429.core.Models.DictEntry;
import com.avionics.a429.core.Models.LabelDict;
import com.avionics.a429.core.Models.SourceItem;

/**
 * 译码器：把源数据项按通道配置与指定版本字典解释为派生字。
 * 原则：八进制 label 只是展示；BNR/BCD/DISCRETE 解释由字典版本决定；
 * 无法转换的值留在原位置并附诊断。
 */
public final class Decoder {
    private Decoder() {}

    public static DecodedWord decode(SourceItem item, ChannelConfig channel, LabelDict dict) {
        String uid = "dw:" + item.channel() + ":" + String.format("%03d", item.seq());
        if (item.rawBits() == null) {
            return new DecodedWord(uid, item.itemId(), item.seq(), item.rxTime(), item.channel(),
                    item.rawText(), -1, channel.bitOrder().name(), -1,
                    false, "???", -1, -1, -1, "UNKNOWN", "?????",
                    dict.version(), false, null, null,
                    null, null, "UNPARSEABLE",
                    item.parseDiag() == null ? "原始字无法解析" : item.parseDiag(),
                    false, "UNPARSEABLE");
        }
        long raw = item.rawBits();
        long w = ArincWord.normalize(raw, channel.bitOrder());
        boolean parityOk = ArincWord.parityOk(w);
        String labelOct = ArincWord.labelOctal(w);
        int sdi = ArincWord.sdi(w);
        int ssm = ArincWord.ssm(w);
        long data = ArincWord.dataField(w);
        String dataHex = String.format("0x%05X", data);

        DictEntry entry = dict.find(labelOct, sdi);
        if (entry == null) {
            return new DecodedWord(uid, item.itemId(), item.seq(), item.rxTime(), item.channel(),
                    ArincWord.hex(raw), raw, channel.bitOrder().name(), w,
                    parityOk, labelOct, ArincWord.labelBits(w), sdi, ssm, "UNKNOWN", dataHex,
                    dict.version(), false, null, null,
                    null, null, "NO_DICT_ENTRY",
                    "字典 " + dict.version() + " 中无 label 0o" + labelOct + " 条目",
                    false, "NO_DICT_ENTRY");
        }

        Ssm.Meaning meaning = Ssm.resolve(ssm, entry.dataType());
        Double value = null;
        String status = "OK";
        String diag = null;
        switch (entry.dataType()) {
            case "BNR" -> {
                long mag = data & 0x3_FFFFL;
                double v = mag * entry.resolution();
                if (Ssm.bnrNegative(data)) v = -v;
                if (entry.rangeMax() != null && Math.abs(v) > entry.rangeMax()) {
                    status = "UNCONVERTIBLE";
                    diag = "BNR 值 " + v + " 超出量程 ±" + entry.rangeMax() + " " + entry.unit();
                    value = null;
                } else {
                    value = v;
                }
            }
            case "BCD" -> {
                long d0 = data & 0xF, d1 = (data >>> 4) & 0xF, d2 = (data >>> 8) & 0xF,
                     d3 = (data >>> 12) & 0xF, d4 = (data >>> 16) & 0x7;
                if (d0 > 9 || d1 > 9 || d2 > 9 || d3 > 9) {
                    status = "UNCONVERTIBLE";
                    diag = "BCD 非法数字（数据域 " + dataHex + "），值留在原位置";
                } else {
                    double v = (d4 * 10000 + d3 * 1000 + d2 * 100 + d1 * 10 + d0) * entry.resolution();
                    if (Ssm.bcdNegative(ssm)) v = -v;
                    value = v;
                }
            }
            case "DISCRETE" -> {
                value = (double) data;   // 位场原值，单位 bits
            }
            default -> {
                status = "UNCONVERTIBLE";
                diag = "未知数据类型 " + entry.dataType();
            }
        }

        boolean eligible = parityOk && meaning == Ssm.Meaning.NORMAL && "OK".equals(status);
        String excludeReason = null;
        if (!parityOk) excludeReason = "PARITY_BAD";
        else if (meaning != Ssm.Meaning.NORMAL) excludeReason = "SSM_" + meaning.name();
        else if (!"OK".equals(status)) excludeReason = status;

        return new DecodedWord(uid, item.itemId(), item.seq(), item.rxTime(), item.channel(),
                ArincWord.hex(raw), raw, channel.bitOrder().name(), w,
                parityOk, labelOct, ArincWord.labelBits(w), sdi, ssm, meaning.name(), dataHex,
                dict.version(), true, entry.entryId(), entry.dataType(),
                value, entry.unit(), status, diag,
                eligible, excludeReason);
    }
}
