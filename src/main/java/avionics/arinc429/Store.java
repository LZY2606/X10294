package avionics.arinc429;

import avionics.arinc429.Segmenter.RateCandidate;
import avionics.arinc429.Segmenter.Segment;
import avionics.arinc429.Segmenter.WordPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 持久化与领域操作：导入事务、分支重解释、候选裁定（乐观版本）、
 * 撤销、依赖失效、事件日志、稳定身份导出/导入。
 */
public class Store {

    public static class ConflictException extends RuntimeException {
        public final long expectedVersion;
        public final long actualVersion;
        public final String currentState;
        public ConflictException(long expected, long actual, String currentState) {
            super("版本冲突: base=" + expected + " current=" + actual);
            this.expectedVersion = expected;
            this.actualVersion = actual;
            this.currentState = currentState;
        }
    }

    public record ImportRec(String id, String name, String channelConfig, String dictVersion, String createdAt) {}

    public record Word(String id, String importId, int seq, long rxNanos, String channel, long raw,
                       boolean parityOk, int labelOctal, String labelText, int sdi, int ssm,
                       long dataField, String dictVersion) {}

    public record Branch(String id, String importId, String name, String parentId,
                         String dictVersion, long version, String createdAt) {}

    public record Event(String id, long seq, String ts, String type, String branchId,
                        String payload, String supersedes) {}

    public record EngValue(String id, String branchId, String wordId, String encoding,
                           String valueText, String unit, String frame, String diagnostic,
                           String dictVersion, String dictEntryId, boolean valid) {}

    public record CandidateView(String id, String segmentId, String branchId, double rateHz,
                                double intervalMs, int votes, String status) {}

    public record SegmentView(String id, String branchId, String channel, int startSeq,
                              int endSeq, boolean gapBefore, String status) {}

    public record DecisionRecord(String id, String branchId, String targetType, String targetId,
                                 String action, long baseVersion, String payload, String status,
                                 String createdAt, boolean undone) {}

    private final Connection c;
    private final ObjectMapper json = new ObjectMapper();

    public Store(Connection c) { this.c = c; }

    private ObjectNode readTree(String s) {
        try {
            return (ObjectNode) json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private ObjectNode evPayload(String... kv) {
        ObjectNode n = json.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n;
    }

    // ---------------- 字典 ----------------

    public void putDict(Dictionary dict) throws SQLException {
        ObjectNode root = json.createObjectNode();
        ArrayNode arr = root.putArray("entries");
        for (Dictionary.Entry e : dict.entries().values()) {
            ObjectNode n = arr.addObject();
            n.put("id", e.id()).put("labelOctal", e.labelOctal()).put("labelText", e.labelText())
             .put("encoding", e.encoding()).put("resolution", e.resolution())
             .put("unit", e.unit()).put("frame", e.frame()).put("description", e.description());
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dicts(version, entries) VALUES(?,?) "
                        + "ON CONFLICT(version) DO UPDATE SET entries=excluded.entries")) {
            ps.setString(1, dict.version);
            ps.setString(2, root.toString());
            ps.executeUpdate();
        }
    }

    public Dictionary getDict(String version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT entries FROM dicts WHERE version=?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                var root = readTree(rs.getString(1));
                Dictionary d = new Dictionary(version);
                for (var n : root.get("entries")) {
                    d.add(new Dictionary.Entry(n.get("id").asText(), n.get("labelOctal").asInt(),
                            n.get("encoding").asText(), n.get("resolution").asDouble(),
                            n.get("unit").asText(), n.get("frame").asText(),
                            n.get("description").asText()));
                }
                return d;
            }
        }
    }

    // ---------------- 导入（失败整体回滚） ----------------

    public ImportRec createImport(String id, String name, String channelConfig, String dictVersion,
                                  List<Long> raws, List<Integer> seqs, List<Long> times,
                                  List<String> channels) throws SQLException {
        c.setAutoCommit(false);
        try {
            String now = Instant.now().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imports(id,name,channel_config,dict_version,created_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, id); ps.setString(2, name); ps.setString(3, channelConfig);
                ps.setString(4, dictVersion); ps.setString(5, now);
                ps.executeUpdate();
            }
            for (int i = 0; i < raws.size(); i++) {
                insertWord(id, "w-" + id + "-" + seqs.get(i), seqs.get(i), times.get(i),
                        channels.get(i), raws.get(i), dictVersion);
            }
            c.commit();
            return getImport(id);
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    public void insertWord(String importId, String wordId, int seq, long rxNanos, String channel,
                           long raw, String dictVersion) throws SQLException {
        Arinc.Decoded d = Arinc.decode(raw);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO words(id,import_id,seq,rx_nanos,channel,raw,parity_ok,label_octal,"
                        + "label_text,sdi,ssm,data_field,dict_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, wordId); ps.setString(2, importId); ps.setInt(3, seq);
            ps.setLong(4, rxNanos); ps.setString(5, channel); ps.setLong(6, d.raw());
            ps.setInt(7, d.parityOk() ? 1 : 0); ps.setInt(8, d.labelOctal());
            ps.setString(9, d.labelOctalText()); ps.setInt(10, d.sdi()); ps.setInt(11, d.ssm());
            ps.setLong(12, d.dataField()); ps.setString(13, dictVersion);
            ps.executeUpdate();
        }
    }

    public ImportRec getImport(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM imports WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ImportRec(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5));
            }
        }
    }

    public List<ImportRec> listImports() throws SQLException {
        List<ImportRec> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM imports ORDER BY created_at,id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new ImportRec(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)));
        }
        return out;
    }

    public List<String> channelsOf(String importId) throws SQLException {
        var root = readTree(getImport(importId).channelConfig());
        List<String> out = new ArrayList<>();
        for (var n : root.get("channels")) out.add(n.asText());
        return out;
    }

    public List<Word> wordsOf(String importId) throws SQLException {
        return queryWords("WHERE import_id=? ORDER BY seq", importId);
    }

    public Word word(String wordId) throws SQLException {
        List<Word> l = queryWords("WHERE id=?", wordId);
        return l.isEmpty() ? null : l.get(0);
    }

    private List<Word> queryWords(String where, String arg) throws SQLException {
        List<Word> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM words " + where)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Word(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getLong(4), rs.getString(5), rs.getLong(6),
                            rs.getInt(7) == 1, rs.getInt(8), rs.getString(9),
                            rs.getInt(10), rs.getInt(11), rs.getLong(12), rs.getString(13)));
                }
            }
        }
        return out;
    }

    // ---------------- 分支 ----------------

    public Branch createBranch(String id, String importId, String name, String parentId,
                               String dictVersion) throws SQLException {
        c.setAutoCommit(false);
        try {
            String now = Instant.now().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO branches(id,import_id,name,parent_id,dict_version,version,created_at)"
                            + " VALUES(?,?,?,?,?,0,?)")) {
                ps.setString(1, id); ps.setString(2, importId); ps.setString(3, name);
                ps.setString(4, parentId); ps.setString(5, dictVersion); ps.setString(6, now);
                ps.executeUpdate();
            }
            recomputeBranch(id);
            appendEventLocal("branch-created", id, evPayload("branch", id, "name", name,
                    "dictVersion", dictVersion, "parent", parentId == null ? "" : parentId));
            c.commit();
            return getBranch(id);
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    public Branch getBranch(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM branches WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Branch(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7));
            }
        }
    }

    public List<Branch> branchesOf(String importId) throws SQLException {
        List<Branch> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM branches WHERE import_id=? ORDER BY created_at")) {
            ps.setString(1, importId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Branch(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getString(7)));
            }
        }
        return out;
    }

    /** 重新生成分支派生层（速率分段候选 + 工程值解释）；来源字不改动。 */
    public void recomputeBranch(String branchId) throws SQLException {
        Branch b = getBranch(branchId);
        List<Word> words = wordsOf(b.importId());
        List<String> channelNames = channelsOf(b.importId());
        Dictionary dict = getDict(b.dictVersion());

        try (PreparedStatement d1 = c.prepareStatement("DELETE FROM rate_candidates WHERE branch_id=?");
             PreparedStatement d2 = c.prepareStatement("DELETE FROM segments WHERE branch_id=?");
             PreparedStatement d3 = c.prepareStatement("DELETE FROM eng_values WHERE branch_id=?")) {
            d1.setString(1, branchId); d1.executeUpdate();
            d2.setString(1, branchId); d2.executeUpdate();
            d3.setString(1, branchId); d3.executeUpdate();
        }

        Map<String, List<WordPoint>> byCh = new LinkedHashMap<>();
        for (String ch : channelNames) byCh.put(ch, new ArrayList<>());
        for (Word w : words) byCh.computeIfAbsent(w.channel(), k -> new ArrayList<>())
                .add(new WordPoint(w.seq(), w.rxNanos(), w.parityOk()));
        for (Map.Entry<String, List<WordPoint>> e : byCh.entrySet()) {
            for (Segment seg : Segmenter.segment(e.getKey(), e.getValue())) {
                String segId = branchId + "-" + seg.id();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO segments(id,branch_id,channel,start_seq,end_seq,gap_before,status)"
                                + " VALUES(?,?,?,?,?,?,'candidate')")) {
                    ps.setString(1, segId); ps.setString(2, branchId);
                    ps.setString(3, seg.channel()); ps.setInt(4, seg.startSeq());
                    ps.setInt(5, seg.endSeq()); ps.setInt(6, seg.gapBefore() ? 1 : 0);
                    ps.executeUpdate();
                }
                for (RateCandidate rc : seg.candidates()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO rate_candidates(id,segment_id,branch_id,rate_hz,interval_ms,"
                                    + "votes,status) VALUES(?,?,?,?,?,?,?)")) {
                        ps.setString(1, branchId + "-" + rc.id()); ps.setString(2, segId);
                        ps.setString(3, branchId); ps.setDouble(4, rc.rateHz());
                        ps.setDouble(5, rc.intervalMs()); ps.setInt(6, rc.votes());
                        ps.setString(7, rc.status());
                        ps.executeUpdate();
                    }
                }
            }
        }

        for (Word w : words) {
            String evId = "ev-" + branchId + "-" + w.seq();
            Dictionary.Entry entry = dict == null ? null : dict.find(w.labelOctal());
            String encoding = "NONE";
            String valueText = "";
            String unit = "";
            String frame = "";
            String diagnostic = null;
            String entryId = "";
            if (!w.parityOk()) {
                encoding = "EXCLUDED";
                diagnostic = "奇偶校验失败，坏奇偶字不进入聚合值";
            } else if (entry == null) {
                encoding = "NO_DICT";
                diagnostic = "字典 " + b.dictVersion() + " 未收录 label " + w.labelText();
            } else {
                entryId = entry.id();
                unit = entry.unit();
                frame = entry.frame();
                encoding = entry.encoding();
                switch (entry.encoding()) {
                    case "BNR" -> valueText = String.format(Locale.ROOT, "%.3f",
                            Arinc.decodeBnr(w.dataField(), entry.resolution()));
                    case "BCD" -> {
                        Arinc.BcdResult r = Arinc.decodeBcd(w.dataField(), entry.resolution());
                        if (r.ok()) valueText = String.format(Locale.ROOT, "%.3f", r.value());
                        else diagnostic = r.diagnostic();
                    }
                    case "DISCRETE" -> valueText = Arinc.discreteBits(w.dataField()).toString();
                    default -> diagnostic = "未知编码 " + entry.encoding();
                }
                if (w.ssm() == 3) {
                    diagnostic = (diagnostic == null ? "" : diagnostic + "; ")
                            + "SSM=3 无效/保留状态，工程值标记可疑";
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eng_values(id,branch_id,word_id,encoding,value_text,unit,frame,"
                            + "diagnostic,dict_version,dict_entry_id,valid) VALUES(?,?,?,?,?,?,?,?,?,?,1)")) {
                ps.setString(1, evId); ps.setString(2, branchId); ps.setString(3, w.id());
                ps.setString(4, encoding); ps.setString(5, valueText); ps.setString(6, unit);
                ps.setString(7, frame); ps.setString(8, diagnostic);
                ps.setString(9, b.dictVersion()); ps.setString(10, entryId);
                ps.executeUpdate();
            }
        }
    }

    // ---------------- 裁定（乐观版本 + 冲突 + 撤销） ----------------

    public record DecisionResult(DecisionRecord decision, Event event) {}

    public synchronized DecisionResult decide(String branchId, String targetType, String targetId,
                                              String action, String payload, long baseVersion)
            throws SQLException {
        Branch b = getBranch(branchId);
        if (b.version() != baseVersion) {
            throw new ConflictException(baseVersion, b.version(), branchStateSummary(branchId));
        }
        c.setAutoCommit(false);
        try {
            String decId = "dec-" + branchId + "-" + (b.version() + 1);
            String now = Instant.now().toString();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO decisions(id,branch_id,target_type,target_id,action,base_version,"
                            + "payload,status,created_at) VALUES(?,?,?,?,?,?,?,'applied',?)")) {
                ps.setString(1, decId); ps.setString(2, branchId); ps.setString(3, targetType);
                ps.setString(4, targetId); ps.setString(5, action); ps.setLong(6, baseVersion);
                ps.setString(7, payload); ps.setString(8, now);
                ps.executeUpdate();
            }
            applyDecision(branchId, targetType, targetId, action, payload);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE branches SET version=version+1 WHERE id=?")) {
                ps.setString(1, branchId);
                ps.executeUpdate();
            }
            ObjectNode ev = evPayload("decision", decId, "targetType", targetType,
                    "targetId", targetId, "action", action, "baseVersion",
                    String.valueOf(baseVersion));
            ev.put("payload", payload);
            Event e = appendEventLocal("decision", branchId, ev);
            c.commit();
            return new DecisionResult(getDecision(decId), e);
        } catch (Exception ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private void applyDecision(String branchId, String targetType, String targetId, String action,
                               String payload) throws SQLException {
        if ("rate-candidate".equals(targetType)) {
            String status = switch (action) {
                case "accept" -> "accepted";
                case "reject" -> "rejected";
                default -> "candidate";
            };
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE rate_candidates SET status=? WHERE id=? AND branch_id=?")) {
                ps.setString(1, status); ps.setString(2, targetId); ps.setString(3, branchId);
                ps.executeUpdate();
            }
        } else if ("eng-value".equals(targetType)) {
            // 参数调整：人工改写工程值展示，但原字与字典命中仍保留
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eng_values SET value_text=?, unit=? WHERE id=? AND branch_id=?")) {
                var n = readTree(payload);
                ps.setString(1, n.path("valueText").asText());
                ps.setString(2, n.path("unit").asText(""));
                ps.setString(3, targetId); ps.setString(4, branchId);
                ps.executeUpdate();
            }
        }
    }

    public synchronized DecisionRecord undo(String branchId, String decisionId, long baseVersion)
            throws SQLException {
        Branch b = getBranch(branchId);
        if (b.version() != baseVersion) {
            throw new ConflictException(baseVersion, b.version(), branchStateSummary(branchId));
        }
        DecisionRecord d = getDecision(decisionId);
        if (d == null || d.undone()) throw new IllegalStateException("裁定不存在或已撤销: " + decisionId);
        c.setAutoCommit(false);
        try {
            // 反向恢复目标状态
            if ("rate-candidate".equals(d.targetType())) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE rate_candidates SET status='candidate' WHERE id=? AND branch_id=?")) {
                    ps.setString(1, d.targetId()); ps.setString(2, branchId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE decisions SET undone=1 WHERE id=?")) {
                ps.setString(1, decisionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE branches SET version=version+1 WHERE id=?")) {
                ps.setString(1, branchId);
                ps.executeUpdate();
            }
            appendEventLocal("decision-undone", branchId,
                    evPayload("decision", decisionId, "restoredTarget", d.targetId()));
            c.commit();
            return getDecision(decisionId);
        } catch (Exception ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(true);
        }
    }

    public DecisionRecord getDecision(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM decisions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new DecisionRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getInt(10) == 1);
            }
        }
    }

    public List<DecisionRecord> decisionsOf(String branchId) throws SQLException {
        List<DecisionRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM decisions WHERE branch_id=? ORDER BY created_at,id")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new DecisionRecord(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10) == 1));
            }
        }
        return out;
    }

    // ---------------- 局部源数据修订：只使依赖失效 ----------------

    /** 只改动一个直接来源（如修正某字奇偶位），失效范围 = 该字工程值 + 所在通道分段。 */
    public synchronized List<String> amendSourceWord(String wordId, long newRaw, String reason)
            throws SQLException {
        Word w = word(wordId);
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE words SET raw=?, parity_ok=?, label_octal=?, label_text=?, sdi=?, ssm=?,"
                            + " data_field=? WHERE id=?")) {
                Arinc.Decoded d = Arinc.decode(newRaw);
                ps.setLong(1, d.raw()); ps.setInt(2, d.parityOk() ? 1 : 0);
                ps.setInt(3, d.labelOctal()); ps.setString(4, d.labelOctalText());
                ps.setInt(5, d.sdi()); ps.setInt(6, d.ssm()); ps.setLong(7, d.dataField());
                ps.setString(8, wordId);
                ps.executeUpdate();
            }
            List<String> affected = new ArrayList<>();
            affected.add(wordId);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eng_values SET valid=0 WHERE word_id=?")) {
                ps.setString(1, wordId);
                affected.add("eng_values:" + ps.executeUpdate());
            }
            for (Branch br : branchesOf(w.importId())) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE segments SET status='stale' WHERE branch_id=? AND channel=?")) {
                    ps.setString(1, br.id()); ps.setString(2, w.channel());
                    ps.executeUpdate();
                }
                affected.add("segments:" + br.id() + ":" + w.channel());
            }
            ObjectNode ev = evPayload("word", wordId, "reason", reason,
                    "affected", String.join(",", affected));
            ev.put("newRaw", String.format("0x%08X", newRaw));
            appendEventLocal("source-amended", null, ev);
            c.commit();
            return affected;
        } catch (Exception ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** 字典版本变化：只使引用该版本的工程值失效，不全局替换旧结论。 */
    public List<String> invalidateByDict(String dictVersion) throws SQLException {
        List<String> affected = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE eng_values SET valid=0 WHERE dict_version=?")) {
            ps.setString(1, dictVersion);
            affected.add("eng_values:" + ps.executeUpdate());
        }
        return affected;
    }

    // ---------------- 事件 ----------------

    private Event appendEventLocal(String type, String branchId, ObjectNode payload)
            throws SQLException {
        long seq;
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM events");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            seq = rs.getLong(1);
        }
        String id = "ev-" + seq;
        String now = Instant.now().toString();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO events(id,seq,ts,type,branch_id,payload) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setLong(2, seq); ps.setString(3, now);
            ps.setString(4, type); ps.setString(5, branchId); ps.setString(6, payload.toString());
            ps.executeUpdate();
        }
        return new Event(id, seq, now, type, branchId, payload.toString(), null);
    }

    public Event appendEvent(String type, String branchId, ObjectNode payload) throws SQLException {
        return appendEventLocal(type, branchId, payload);
    }

    public List<Event> events(String branchId, Long upToSeq) throws SQLException {
        List<Event> out = new ArrayList<>();
        String sql = "SELECT * FROM events WHERE (? IS NULL OR branch_id=? OR branch_id IS NULL)"
                + (upToSeq != null ? " AND seq<=" + upToSeq : "") + " ORDER BY seq";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, branchId); ps.setString(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Event(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
            }
        }
        return out;
    }

    public Event event(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM events WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Event(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7));
            }
        }
    }

    /** 回到任意旧事件：重放 <= seq 的裁定，恢复当时的候选状态与依赖版本。 */
    public Map<String, String> candidateStatusAsOf(String branchId, long seq) throws SQLException {
        Map<String, String> status = new LinkedHashMap<>();
        for (CandidateView cv : candidatesOf(branchId)) status.put(cv.id(), "candidate");
        for (Event e : events(branchId, seq)) {
            if (!"decision".equals(e.type())) continue;
            var n = readTree(e.payload());
            String targetId = n.path("targetId").asText();
            if (!status.containsKey(targetId)) continue;
            String action = n.path("action").asText();
            status.put(targetId, switch (action) {
                case "accept" -> "accepted";
                case "reject" -> "rejected";
                default -> "candidate";
            });
        }
        // 撤销事件：恢复 candidate
        for (Event e : events(branchId, seq)) {
            if (!"decision-undone".equals(e.type())) continue;
            var n = readTree(e.payload());
            String decId = n.path("decision").asText();
            DecisionRecord d = getDecision(decId);
            if (d != null && status.containsKey(d.targetId())) status.put(d.targetId(), "candidate");
        }
        return status;
    }

    // ---------------- 查询 ----------------

    public List<SegmentView> segmentsOf(String branchId) throws SQLException {
        List<SegmentView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM segments WHERE branch_id=? ORDER BY channel,start_seq")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SegmentView(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6) == 1,
                        rs.getString(7)));
            }
        }
        return out;
    }

    public List<CandidateView> candidatesOf(String branchId) throws SQLException {
        List<CandidateView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM rate_candidates WHERE branch_id=? ORDER BY segment_id,id")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new CandidateView(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6),
                        rs.getString(7)));
            }
        }
        return out;
    }

    public List<EngValue> engValuesOf(String branchId) throws SQLException {
        List<EngValue> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM eng_values WHERE branch_id=? ORDER BY id")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readEngValue(rs));
            }
        }
        return out;
    }

    public EngValue engValueFor(String branchId, String wordId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM eng_values WHERE branch_id=? AND word_id=?")) {
            ps.setString(1, branchId); ps.setString(2, wordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEngValue(rs) : null;
            }
        }
    }

    private EngValue readEngValue(ResultSet rs) throws SQLException {
        return new EngValue(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getInt(11) == 1);
    }

    public String branchStateSummary(String branchId) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (CandidateView cv : candidatesOf(branchId)) {
            sb.append(cv.id()).append('=').append(cv.status()).append('\n');
        }
        return sb.toString();
    }

    // ---------------- 导出 / 导入（稳定身份保持） ----------------

    private static final String[] TABLES = {"imports", "words", "dicts", "branches", "segments",
            "rate_candidates", "eng_values", "decisions", "events"};

    public ObjectNode exportAll() throws SQLException {
        ObjectNode root = json.createObjectNode();
        root.put("format", "arinc429-review-export");
        root.put("schemaVersion", 2);
        for (String table : TABLES) {
            ArrayNode arr = root.putArray(table);
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                while (rs.next()) {
                    ObjectNode row = arr.addObject();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String col = meta.getColumnName(i);
                        Object v = rs.getObject(i);
                        if (v == null) row.putNull(col);
                        else if (v instanceof Number num) {
                            if (v instanceof Double || v instanceof Float) row.put(col, num.doubleValue());
                            else row.put(col, num.longValue());
                        } else row.put(col, v.toString());
                    }
                }
            }
        }
        return root;
    }

    /** 导入导出档案：身份、顺序、来源关系原样恢复。 */
    public void importAll(ObjectNode root) throws SQLException {
        c.setAutoCommit(false);
        try {
            for (String table : TABLES) {
                ArrayNode arr = (ArrayNode) root.get(table);
                if (arr == null) continue;
                for (var rowNode : arr) {
                    ObjectNode row = (ObjectNode) rowNode;
                    StringBuilder cols = new StringBuilder();
                    StringBuilder marks = new StringBuilder();
                    List<com.fasterxml.jackson.databind.JsonNode> vals = new ArrayList<>();
                    row.fields().forEachRemaining(en -> {
                        cols.append(cols.isEmpty() ? "" : ",").append(en.getKey());
                        marks.append(marks.isEmpty() ? "" : ",").append("?");
                        vals.add(en.getValue());
                    });
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT OR REPLACE INTO " + table + "(" + cols + ") VALUES(" + marks + ")")) {
                        for (int i = 0; i < vals.size(); i++) {
                            var v = vals.get(i);
                            if (v == null || v.isNull()) ps.setObject(i + 1, null);
                            else if (v.isNumber()) {
                                if (v.isFloatingPointNumber()) ps.setDouble(i + 1, v.doubleValue());
                                else ps.setLong(i + 1, v.longValue());
                            } else ps.setString(i + 1, v.asText());
                        }
                        ps.executeUpdate();
                    }
                }
            }
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** 删除本地数据库内容（保留表结构），用于导出-删除-再导入流程。 */
    public void resetAll() throws SQLException {
        c.setAutoCommit(false);
        try (var st = c.createStatement()) {
            for (String t : new String[]{"events", "decisions", "eng_values", "rate_candidates",
                    "segments", "branches", "words", "imports", "dicts"}) {
                st.execute("DELETE FROM " + t);
            }
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }
}
