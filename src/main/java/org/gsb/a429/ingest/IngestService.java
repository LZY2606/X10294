package org.gsb.a429.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gsb.a429.arinc.Arinc;
import org.gsb.a429.casepkg.CaseModel;
import org.gsb.a429.casepkg.CaseReader;
import org.gsb.a429.db.Db;

import java.util.Map;

/**
 * 把案例包导入原始层。导入在单事务内进行，任何校验失败都整体回滚，不留半份记录。
 * 稳定身份：raw:MATERIAL:rev:wordId；字典条目沿用案例 sid。
 */
public class IngestService {
    private final Db db;
    private final ObjectMapper mapper;

    public IngestService(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public static String rawSid(String materialId, int rev, String wordId) {
        return "raw:" + materialId + ":" + rev + ":" + wordId;
    }

    /** 全新导入案例包；要求库为空，否则拒绝（防止覆盖原始证据）。 */
    public synchronized void importCase(CaseReader reader) {
        if (!db.isEmpty()) {
            throw new IllegalStateException("database already contains material; refuse to overwrite");
        }
        CaseModel.Manifest man = reader.manifest();
        try {
            for (CaseModel.ChannelFile ch : reader.channels()) {
                boolean rev = "reversed32".equalsIgnoreCase(ch.bitOrder);
                db.upsertChannel(ch.id, ch.name, rev, ch.note);
            }
            db.addMaterialRevision(man.materialId, man.baselineRevision,
                    "基线导入：原位序列/接收时间/通道/奇偶/label/SDI/SSM/数据域/字典版本均保留");

            int expectedSeq = 1;
            for (CaseModel.WordFile w : reader.words()) {
                if (w.seq != expectedSeq) {
                    throw new IllegalArgumentException("原位序列不连续: 期望 " + expectedSeq
                            + " 实际 " + w.seq + " (" + w.wordId + ")");
                }
                expectedSeq++;
                int word = parseHex32(w.word32Hex);
                validateWord(w, word);
                db.addRawWord(rawSid(man.materialId, man.baselineRevision, w.wordId),
                        man.materialId, man.baselineRevision, w.wordId, w.seq, w.tMs,
                        w.channel, word, w.note == null ? "" : w.note);
            }

            CaseModel.Dictionaries dicts = reader.dictionaries();
            for (CaseModel.Version v : dicts.versions) {
                db.addDictVersion(v.version, v.note, v.supersedes);
            }
            for (CaseModel.Entry e : dicts.entries) {
                db.addDictEntry(e.sid, e.dictVersion,
                        Arinc.reverse8(Integer.parseInt(e.labelOctal, 8)),
                        e.sdi, e.encoding, mapper.writeValueAsString(e.spec), e.name);
            }

            db.addBranch(man.baselineBranch, "基线分支", null, null,
                    man.baselineRevision, man.baselineDict, "初始导入后的只读基线");
            db.addEvent("evt:" + man.baselineBranch + ":1", man.baselineBranch, 1,
                    "IMPORT", man.materialId,
                    mapper.writeValueAsString(Map.of(
                            "materialId", man.materialId,
                            "revision", man.baselineRevision,
                            "dictVersion", man.baselineDict,
                            "wordCount", reader.words().size(),
                            "rateNominalHz", man.rateNominalHz,
                            "gapMultiple", man.gapMultiple)),
                    "ingest", 0.0, null, "原始层导入（含全部来源字段）");
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        } catch (Exception e) {
            db.rollback();
            throw new RuntimeException("import failed, rolled back", e);
        }
    }

    private void validateWord(CaseModel.WordFile w, int word) {
        boolean reversed = w.reversed32;
        int canonical = reversed ? Arinc.reverse32(word) : word;
        int labelCode = Arinc.labelCode(canonical);
        if (!String.format("%03o", labelCode).equals(w.labelOctal)) {
            throw new IllegalArgumentException("word32Hex 与声明 labelOctal 不一致: " + w.wordId);
        }
        if (Arinc.sdi(canonical) != w.sdi) {
            throw new IllegalArgumentException("SDI 不一致: " + w.wordId);
        }
        if (Arinc.ssm(canonical) != w.ssm) {
            throw new IllegalArgumentException("SSM 不一致: " + w.wordId);
        }
        if (Arinc.field(canonical, 11, 29) != Integer.parseInt(w.dataHex, 16)) {
            throw new IllegalArgumentException("数据域 dataHex 不一致: " + w.wordId);
        }
        // 奇偶：除非 note/字段明确是坏字，否则必须奇校验
        boolean declaredBad = w.note != null && w.note.contains("奇偶错误");
        if (Arinc.parityValid(canonical) == declaredBad) {
            throw new IllegalArgumentException("奇偶结果与声明不符: " + w.wordId);
        }
    }

    private int parseHex32(String hex) {
        long v = Long.parseLong(hex, 16);
        if (v < 0L || v > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("not a 32-bit word: " + hex);
        }
        return (int) v;
    }
}
