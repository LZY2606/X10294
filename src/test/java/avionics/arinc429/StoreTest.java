package avionics.arinc429;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {

    private Connection conn;
    private Store store;

    @BeforeEach
    void setUp() throws Exception {
        conn = Db.open(":memory:");
        store = new Store(conn);
    }

    private String loadCase() throws Exception {
        return new CaseLoader(store).load();
    }

    @Test
    void importFailureRollsBackWholeBatch() throws Exception {
        Dictionary d = new Dictionary("dict-v1");
        d.add(new Dictionary.Entry("de-1", 035, "BNR", 0.01, "deg", "body", "x"));
        store.putDict(d);
        String cfg = "{\"channels\":[\"A\"]}";
        // 两个相同 seq -> UNIQUE 冲突 -> 整体回滚
        assertThrows(Exception.class, () -> store.createImport("imp-x", "x", cfg, "dict-v1",
                List.of(1L, 2L), List.of(1, 1), List.of(0L, 100L), List.of("A", "A")));
        assertNull(store.getImport("imp-x"));
        assertTrue(store.wordsOf("imp-x").isEmpty()); // 不留半份记录
    }

    @Test
    void casePackageJudgmentsAllPass() throws Exception {
        loadCase();
        List<Store.Event> judgments = store.events(null, null).stream()
                .filter(e -> "judgment".equals(e.type())).toList();
        assertEquals(9, judgments.size());
        for (Store.Event e : judgments) {
            assertTrue(e.payload().contains("\"result\":\"pass\""),
                    "判定失败: " + e.payload());
        }
    }

    @Test
    void baselinePreservesAllSourceFields() throws Exception {
        String importId = loadCase();
        List<Store.Word> words = store.wordsOf(importId);
        assertEquals(14, words.size());
        Store.Word w5 = words.get(4);
        // 案例重放中的局部源数据修订已修正 seq5 奇偶位；
        // 修订前“坏奇偶字不进入聚合值”的结论保留在判定事件中
        assertTrue(w5.parityOk());
        assertTrue(store.events(null, null).stream().anyMatch(e ->
                e.payload().contains("bad-parity-excluded-from-aggregate")
                        && e.payload().contains("EXCLUDED")));
        assertEquals("035", w5.labelText());
        assertEquals("dict-v1", w5.dictVersion());
        Store.Word w7 = words.get(6);
        assertEquals("270", w7.labelText()); // 位序反传展示，底层位保留
        assertEquals(3, words.get(5).ssm()); // SSM 无效状态保留
    }

    @Test
    void dictVersionDecidesEngineeringValue() throws Exception {
        String importId = loadCase();
        Store.EngValue v1 = store.engValueFor("br-baseline", "w-" + importId + "-1");
        Store.EngValue v2 = store.engValueFor("br-param-revision", "w-" + importId + "-1");
        assertEquals("15.000", v1.valueText());
        assertEquals("1.500", v2.valueText());
        assertEquals("de-035-v1", v1.dictEntryId());
        assertEquals("de-035-v2", v2.dictEntryId());
    }

    @Test
    void badParityWordExcludedFromAggregation() throws Exception {
        // 案例在源数据修订前，seq5 奇偶错误 -> EXCLUDED；修订事件链保留
        loadCase();
        List<Store.Event> events = store.events(null, null);
        Store.Event j = events.stream()
                .filter(e -> e.payload().contains("bad-parity-excluded-from-aggregate"))
                .findFirst().orElseThrow();
        assertTrue(j.payload().contains("EXCLUDED"));
    }

    @Test
    void decisionConflictKeepsBothOutcomes() throws Exception {
        loadCase();
        // 案例已含一次冲突：accept 被采纳，reject 保留在 decision-conflict 事件
        Store.DecisionRecord applied = store.decisionsOf("br-baseline").stream()
                .filter(d -> "applied".equals(d.status())).findFirst().orElseThrow();
        assertEquals("accept", applied.action());
        assertTrue(store.events("br-baseline", null).stream()
                .anyMatch(e -> "decision-conflict".equals(e.type())
                        && e.payload().contains("reject")));
        // 再制造一次冲突：同一 baseVersion 两次提交
        long base = store.getBranch("br-baseline").version();
        store.decide("br-baseline", "rate-candidate", "br-baseline-A-s1-c0", "accept", "{}", base);
        assertThrows(Store.ConflictException.class, () ->
                store.decide("br-baseline", "rate-candidate", "br-baseline-A-s1-c0", "reject", "{}", base));
    }

    @Test
    void undoRestoresPreviousBranchState() throws Exception {
        loadCase();
        long base = store.getBranch("br-baseline").version();
        Store.DecisionRecord d = store.decide("br-baseline", "rate-candidate",
                "br-baseline-A-s1-c0", "accept", "{}", base).decision();
        assertEquals("accepted", store.candidatesOf("br-baseline").stream()
                .filter(c -> c.id().equals("br-baseline-A-s1-c0")).findFirst().orElseThrow().status());
        store.undo("br-baseline", d.id(), base + 1);
        assertEquals("candidate", store.candidatesOf("br-baseline").stream()
                .filter(c -> c.id().equals("br-baseline-A-s1-c0")).findFirst().orElseThrow().status());
        assertTrue(store.getDecision(d.id()).undone());
    }

    @Test
    void sourceAmendInvalidatesOnlyDependents() throws Exception {
        String importId = loadCase();
        Store.Word w8 = store.word("w-" + importId + "-8");
        List<String> affected = store.amendSourceWord(w8.id(), w8.raw() ^ 0x80000000L, "测试修订");
        assertTrue(affected.contains(w8.id()));
        assertTrue(affected.stream().anyMatch(a -> a.contains(":A")));
        assertFalse(affected.stream().anyMatch(a -> a.contains(":B"))); // 不影响通道 B
        assertFalse(store.engValueFor("br-baseline", w8.id()).valid());
        assertTrue(store.engValueFor("br-baseline", "w-" + importId + "-1").valid());
    }

    @Test
    void exportResetImportKeepsIdentityOrderAndRelations() throws Exception {
        String importId = loadCase();
        ObjectNode dump = store.exportAll();
        List<Store.Word> before = store.wordsOf(importId);
        List<Store.Event> eventsBefore = store.events(null, null);

        store.resetAll();
        assertTrue(store.listImports().isEmpty());
        store.importAll(dump);

        List<Store.Word> after = store.wordsOf(importId);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).id(), after.get(i).id());
            assertEquals(before.get(i).seq(), after.get(i).seq());
            assertEquals(before.get(i).raw(), after.get(i).raw());
        }
        List<Store.Event> eventsAfter = store.events(null, null);
        assertEquals(eventsBefore.size(), eventsAfter.size());
        assertEquals("accepted", store.candidatesOf("br-baseline").stream()
                .filter(c -> c.id().equals("br-baseline-A-s0-c0")).findFirst().orElseThrow().status());
        assertEquals("1.500", store.engValueFor("br-param-revision",
                "w-" + importId + "-1").valueText());
    }

    @Test
    void historicalEventViewReplaysOldConclusions() throws Exception {
        loadCase();
        // 找到冲突前的事件：当时 br-baseline-A-s0-c0 还是 candidate
        Store.Event before = store.events("br-baseline", null).stream()
                .filter(e -> "branch-recomputed".equals(e.type())).findFirst().orElseThrow();
        var asOf = store.candidateStatusAsOf("br-baseline", before.seq());
        assertEquals("candidate", asOf.get("br-baseline-A-s0-c0"));
        // 最新状态已是 accepted —— 历史视角不被最新结果覆盖
        var now = store.candidateStatusAsOf("br-baseline", Long.MAX_VALUE);
        assertEquals("accepted", now.get("br-baseline-A-s0-c0"));
    }
}
