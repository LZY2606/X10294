package avionics.arinc429;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 载入离线案例包「字路译码台：ARINC 429 标签、奇偶与速率分段证据」：
 * 基线导入 -> 基线判定事件 -> 参数修订 -> 局部源数据改动 -> 冲突人工决定。
 * 每个规则的判定依据写成机器可读取的 judgment 事件。
 */
public class CaseLoader {

    public static final String RESOURCE = "/cases/arinc429-word-decode-baseline.json";

    private final Store store;
    private final ObjectMapper json = new ObjectMapper();

    public CaseLoader(Store store) { this.store = store; }

    /** 由字域装配 32 位原始字（案例包用；导入时仍按原始字存储）。 */
    public static long assemble(int labelOctal, boolean wireLabelCorrect, int sdi, long data,
                                int ssm, boolean parityGood) {
        int labelByte = wireLabelCorrect ? (Integer.reverse(labelOctal) >>> 24) : labelOctal;
        long raw = (labelByte & 0xFFL)
                | (((long) sdi & 0x3L) << 8)
                | ((data & 0x7FFFFL) << 10)
                | (((long) ssm & 0x3L) << 29);
        long parity = (Long.bitCount(raw) % 2 == 0) ? 1 : 0; // 奇校验
        if (!parityGood) parity ^= 1;
        return raw | (parity << 31);
    }

    /** 幂等：已载入则跳过。返回基线导入 id。 */
    public synchronized String load() throws Exception {
        JsonNode root;
        try (InputStream in = CaseLoader.class.getResourceAsStream(RESOURCE)) {
            root = json.readTree(in);
        }
        String importId = root.get("importId").asText();
        if (store.getImport(importId) != null) return importId;

        for (JsonNode d : root.get("dicts")) {
            Dictionary dict = new Dictionary(d.get("version").asText());
            for (JsonNode e : d.get("entries")) {
                dict.add(new Dictionary.Entry(e.get("id").asText(),
                        Integer.parseInt(e.get("label").asText(), 8),
                        e.get("encoding").asText(), e.get("resolution").asDouble(),
                        e.get("unit").asText(), e.get("frame").asText(),
                        e.get("description").asText()));
            }
            store.putDict(dict);
        }

        ObjectNode chanCfg = json.createObjectNode();
        var arr = chanCfg.putArray("channels");
        for (JsonNode ch : root.get("channels")) arr.add(ch.asText());

        List<Long> raws = new ArrayList<>();
        List<Integer> seqs = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        List<String> chans = new ArrayList<>();
        for (JsonNode w : root.get("words")) {
            int seq = w.get("seq").asInt();
            seqs.add(seq);
            times.add(w.get("tMs").asLong() * 1_000_000L);
            chans.add(w.get("channel").asText());
            raws.add(assemble(
                    Integer.parseInt(w.get("label").asText(), 8),
                    !w.has("wireLabelCorrect") || w.get("wireLabelCorrect").asBoolean(),
                    w.get("sdi").asInt(), w.get("data").asLong(), w.get("ssm").asInt(),
                    "good".equals(w.get("parity").asText())));
        }
        String dictV1 = root.get("baselineDict").asText();
        store.createImport(importId, root.get("importName").asText(),
                chanCfg.toString(), dictV1, raws, seqs, times, chans);
        store.appendEvent("import", null, payload("import", importId,
                "words", String.valueOf(raws.size()), "dictVersion", dictV1,
                "note", "原位序列、接收时间、通道、奇偶结果、label、SDI、SSM、数据域和字典版本均保留"));

        store.createBranch("br-baseline", importId, "基线审阅", null, dictV1);
        baselineJudgments(importId);
        replayMutations(root, importId);
        return importId;
    }

    private ObjectNode payload(String... kv) {
        ObjectNode n = json.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n;
    }

    private void judgment(String rule, boolean pass, String evidence) throws SQLException {
        store.appendEvent("judgment", null,
                payload("rule", rule, "result", pass ? "pass" : "fail", "evidence", evidence));
    }

    private void baselineJudgments(String importId) throws SQLException {
        List<Store.EngValue> values = store.engValuesOf("br-baseline");
        List<Store.Word> words = store.wordsOf(importId);

        Store.EngValue seq5 = values.stream().filter(v -> v.wordId().endsWith("-5")).findFirst().orElseThrow();
        judgment("bad-parity-excluded-from-aggregate",
                "EXCLUDED".equals(seq5.encoding()),
                "seq5 奇偶错误，encoding=" + seq5.encoding() + "，坏奇偶字不进入聚合值");

        Store.EngValue seq6 = values.stream().filter(v -> v.wordId().endsWith("-6")).findFirst().orElseThrow();
        judgment("ssm-invalid-flagged",
                seq6.diagnostic() != null && seq6.diagnostic().contains("SSM"),
                "seq6 SSM=3 无效状态，diagnostic=" + seq6.diagnostic());

        Store.EngValue seq4 = values.stream().filter(v -> v.wordId().endsWith("-4")).findFirst().orElseThrow();
        Store.Word w4 = words.stream().filter(w -> w.seq() == 4).findFirst().orElseThrow();
        judgment("bcd-illegal-kept-in-place",
                seq4.diagnostic() != null && seq4.diagnostic().contains("非法") && w4.dataField() == 4773,
                "seq4 BCD 非法数字保留原位，raw=0x" + Long.toHexString(w4.raw()) + "，diagnostic=" + seq4.diagnostic());

        Store.EngValue seq2 = values.stream().filter(v -> v.wordId().endsWith("-2")).findFirst().orElseThrow();
        judgment("bnr-negative-decoded",
                seq2.valueText().startsWith("-12.34"),
                "seq2 BNR 负值=" + seq2.valueText() + " " + seq2.unit());

        Store.Word w7 = words.stream().filter(w -> w.seq() == 7).findFirst().orElseThrow();
        judgment("octal-display-keeps-bits",
                "270".equals(w7.labelText()) && w7.raw() != 0,
                "seq7 位序反转：线上字节未反转，八进制展示 270 不改变底层位 raw=0x"
                        + String.format("%08X", w7.raw()));

        List<Store.SegmentView> segs = store.segmentsOf("br-baseline").stream()
                .filter(s -> s.channel().equals("A")).toList();
        boolean hasGap = segs.stream().anyMatch(Store.SegmentView::gapBefore);
        long candCount = store.candidatesOf("br-baseline").stream()
                .filter(cv -> cv.segmentId().contains("-A-")).count();
        judgment("rate-gap-keeps-candidates",
                segs.size() >= 2 && hasGap && candCount >= 2,
                "通道A 分段=" + segs.size() + "（含采集缺口边界），速率候选=" + candCount
                        + "，速率变化与采集缺口保留分段候选");
    }

    private void replayMutations(JsonNode root, String importId) throws Exception {
        for (JsonNode m : root.get("mutations")) {
            switch (m.get("type").asText()) {
                case "param-revision" -> {
                    String branch = m.get("branch").asText();
                    String dictV2 = m.get("dictVersion").asText();
                    store.createBranch(branch, importId, "参数修订重解释", "br-baseline", dictV2);
                    store.appendEvent("dict-revised", branch, payload("dictVersion", dictV2,
                            "changedEntries", "de-035", "note", m.get("note").asText()));
                    Store.EngValue v1 = store.engValueFor("br-baseline",
                            "w-" + importId + "-1");
                    Store.EngValue v2 = store.engValueFor(branch, "w-" + importId + "-1");
                    judgment("dict-version-decides-encoding",
                            !v1.valueText().equals(v2.valueText())
                                    && "dict-v1".equals(v1.dictVersion())
                                    && "dict-v2".equals(v2.dictVersion()),
                            "seq1 在 dict-v1=" + v1.valueText() + " " + v1.unit()
                                    + "，dict-v2=" + v2.valueText() + " " + v2.unit()
                                    + "；BNR/BCD/discrete 解释由字典版本决定，旧分支结论保留");
                }
                case "source-amend" -> {
                    int seq = m.get("wordSeq").asInt();
                    String wordId = "w-" + importId + "-" + seq;
                    Store.Word w = store.word(wordId);
                    long fixed = w.raw() ^ 0x80000000L; // 翻转奇偶位
                    List<String> affected = store.amendSourceWord(wordId, fixed,
                            m.get("reason").asText());
                    store.recomputeBranch("br-baseline");
                    store.appendEvent("branch-recomputed", "br-baseline",
                            payload("reason", "source-amend", "word", wordId));
                    boolean scopeOk = affected.stream().anyMatch(a -> a.equals(wordId))
                            && affected.stream().allMatch(a -> a.equals(wordId)
                                    || a.startsWith("eng_values") || a.contains(":A"));
                    judgment("amend-scope-limited", scopeOk,
                            "只改动一个直接来源 " + wordId + "，失效范围=" + String.join(",", affected));
                }
                case "conflicting-decisions" -> {
                    String branch = m.get("branch").asText();
                    String target = m.get("target").asText();
                    long base = store.getBranch(branch).version();
                    store.decide(branch, "rate-candidate", target,
                            m.get("first").get("action").asText(),
                            "{\"by\":\"" + m.get("first").get("by").asText() + "\"}", base);
                    try {
                        store.decide(branch, "rate-candidate", target,
                                m.get("second").get("action").asText(),
                                "{\"by\":\"" + m.get("second").get("by").asText() + "\"}", base);
                        judgment("conflict-both-kept", false, "第二次裁定未触发版本冲突");
                    } catch (Store.ConflictException conflict) {
                        store.appendEvent("decision-conflict", branch,
                                payload("target", target,
                                        "accepted", m.get("first").get("action").asText()
                                                + " by " + m.get("first").get("by").asText(),
                                        "rejected", m.get("second").get("action").asText()
                                                + " by " + m.get("second").get("by").asText(),
                                        "baseVersion", String.valueOf(base),
                                        "currentVersion",
                                        String.valueOf(conflict.actualVersion)));
                        Store.DecisionRecord applied = store.decisionsOf(branch).stream()
                                .filter(d -> d.targetId().equals(target) && "applied".equals(d.status()))
                                .findFirst().orElseThrow();
                        judgment("conflict-both-kept",
                                "accept".equals(applied.action()),
                                "被采纳=" + applied.action() + "（decision " + applied.id()
                                        + "），未采纳=reject 保留在 decision-conflict 事件");
                    }
                }
                default -> throw new IllegalArgumentException("未知 mutation: " + m);
            }
        }
    }
}
