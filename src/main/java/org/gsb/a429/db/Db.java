package org.gsb.a429.db;

import org.gsb.a429.model.Rows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite 持久层。schema 迁移走显式版本号，状态迁移测试针对 v1 -> v2。 */
public class Db implements AutoCloseable {

    public static final int CURRENT_SCHEMA = 2;

    private final Connection conn;

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            migrate();
        } catch (Exception e) {
            throw new RuntimeException("cannot open db " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    private void migrate() throws SQLException {
        boolean hasMeta;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")) {
            hasMeta = rs.next();
        }
        int version = 0;
        if (hasMeta) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
                if (rs.next()) {
                    version = Integer.parseInt(rs.getString(1));
                }
            }
        }
        if (version == 0) {
            createV2();
        } else if (version == 1) {
            migrateV1ToV2();
        }
        conn.commit();
    }

    private void createV2() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO meta(key,value) VALUES('schema_version','2')");
            st.executeUpdate("CREATE TABLE channel ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, bit_reversed INTEGER NOT NULL, "
                    + "note TEXT NOT NULL DEFAULT '')");
            st.executeUpdate("CREATE TABLE material_revision ("
                    + "material_id TEXT NOT NULL, seq INTEGER NOT NULL, note TEXT NOT NULL, "
                    + "PRIMARY KEY(material_id, seq))");
            st.executeUpdate("CREATE TABLE raw_word ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL UNIQUE, "
                    + "material_id TEXT NOT NULL, revision_seq INTEGER NOT NULL, word_id TEXT NOT NULL, "
                    + "seq INTEGER NOT NULL, recv_time_ms REAL NOT NULL, channel_id TEXT NOT NULL, "
                    + "word32 INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')");
            st.executeUpdate("CREATE INDEX idx_raw_word_rev ON raw_word(material_id, revision_seq)");
            st.executeUpdate("CREATE TABLE dict_version ("
                    + "version TEXT PRIMARY KEY, note TEXT NOT NULL, supersedes TEXT)");
            st.executeUpdate("CREATE TABLE dict_entry ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL UNIQUE, "
                    + "dict_version TEXT NOT NULL, label_code INTEGER NOT NULL, sdi INTEGER, "
                    + "encoding TEXT NOT NULL, spec_json TEXT NOT NULL, name TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE branch ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_branch_id TEXT, "
                    + "parent_event_seq INTEGER, material_seq INTEGER NOT NULL, "
                    + "dict_version TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')");
            st.executeUpdate("CREATE TABLE event ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL UNIQUE, "
                    + "branch_id TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, "
                    + "target_sid TEXT, payload_json TEXT NOT NULL, actor TEXT NOT NULL, "
                    + "ts_ms REAL NOT NULL, parent_event_sid TEXT, basis_label TEXT, "
                    + "UNIQUE(branch_id, seq))");
            st.executeUpdate("CREATE INDEX idx_event_branch ON event(branch_id, seq)");
        }
    }

    /**
     * v1（“字路译码台”早期遗留库）只保留通道与原始字，且没有稳定 sid。
     * 迁移到 v2：补齐 sid、分支/事件/字典表与全部来源字段；迁移本身包在一个事务里，失败回滚。
     */
    private void migrateV1ToV2() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE raw_word RENAME TO raw_word_v1");
            st.executeUpdate("CREATE TABLE raw_word ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL UNIQUE, "
                    + "material_id TEXT NOT NULL, revision_seq INTEGER NOT NULL, word_id TEXT NOT NULL, "
                    + "seq INTEGER NOT NULL, recv_time_ms REAL NOT NULL, channel_id TEXT NOT NULL, "
                    + "word32 INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')");
            st.executeUpdate("INSERT INTO raw_word(sid, material_id, revision_seq, word_id, "
                    + "seq, recv_time_ms, channel_id, word32, note) "
                    + "SELECT 'mig-w' || id, 'MIG', 1, 'LEGACY-' || seq, seq, "
                    + "recv_time_ms, channel_id, word32, COALESCE(note,'') FROM raw_word_v1");
            st.executeUpdate("INSERT INTO material_revision(material_id, seq, note) "
                    + "VALUES('MIG', 1, 'migrated from schema v1')");
            st.executeUpdate("CREATE TABLE dict_version (version TEXT PRIMARY KEY, note TEXT, supersedes TEXT)");
            st.executeUpdate("CREATE TABLE dict_entry (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "sid TEXT NOT NULL UNIQUE, dict_version TEXT NOT NULL, label_code INTEGER NOT NULL, "
                    + "sdi INTEGER, encoding TEXT NOT NULL, spec_json TEXT NOT NULL, name TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE branch (id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "parent_branch_id TEXT, parent_event_seq INTEGER, material_seq INTEGER NOT NULL, "
                    + "dict_version TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')");
            st.executeUpdate("CREATE TABLE event (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "sid TEXT NOT NULL UNIQUE, branch_id TEXT NOT NULL, seq INTEGER NOT NULL, "
                    + "type TEXT NOT NULL, target_sid TEXT, payload_json TEXT NOT NULL, actor TEXT NOT NULL, "
                    + "ts_ms REAL NOT NULL, parent_event_sid TEXT, basis_label TEXT, "
                    + "UNIQUE(branch_id, seq))");
            st.executeUpdate("CREATE INDEX idx_event_branch ON event(branch_id, seq)");
            st.executeUpdate("CREATE INDEX idx_raw_word_rev ON raw_word(material_id, revision_seq)");
            st.executeUpdate("ALTER TABLE channel RENAME TO channel_v1");
            st.executeUpdate("CREATE TABLE channel (id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "bit_reversed INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')");
            st.executeUpdate("INSERT INTO channel(id,name,bit_reversed,note) "
                    + "SELECT id, name, 0, COALESCE(note,'') FROM channel_v1");
            st.executeUpdate("DROP TABLE raw_word_v1");
            st.executeUpdate("DROP TABLE channel_v1");
            st.executeUpdate("UPDATE meta SET value='2' WHERE key='schema_version'");
        }
    }

    public int schemaVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void commit() {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- channel ----------
    public void upsertChannel(String id, String name, boolean bitReversed, String note) {
        exec("INSERT INTO channel(id,name,bit_reversed,note) VALUES(?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET name=excluded.name, "
                        + "bit_reversed=excluded.bit_reversed, note=excluded.note",
                id, name, bitReversed ? 1 : 0, note == null ? "" : note);
    }

    public List<Rows.Channel> channels() {
        List<Rows.Channel> out = new ArrayList<>();
        query(rs -> new Rows.Channel(rs.getString("id"), rs.getString("name"),
                        rs.getInt("bit_reversed") == 1, rs.getString("note")),
                out, "SELECT * FROM channel ORDER BY id");
        return out;
    }

    // ---------- material / raw words ----------
    public void addMaterialRevision(String materialId, int seq, String note) {
        exec("INSERT INTO material_revision(material_id,seq,note) VALUES(?,?,?)",
                materialId, seq, note);
    }

    public List<Rows.MaterialRevision> materialRevisions() {
        List<Rows.MaterialRevision> out = new ArrayList<>();
        query(rs -> new Rows.MaterialRevision(rs.getString("material_id"), rs.getInt("seq"),
                        rs.getString("note")), out,
                "SELECT * FROM material_revision ORDER BY material_id, seq");
        return out;
    }

    public void addRawWord(String sid, String materialId, int revisionSeq, String wordId,
                           int seq, double recvTimeMs, String channelId, int word32, String note) {
        exec("INSERT INTO raw_word(sid,material_id,revision_seq,word_id,seq,recv_time_ms,"
                        + "channel_id,word32,note) VALUES(?,?,?,?,?,?,?,?,?)",
                sid, materialId, revisionSeq, wordId, seq, recvTimeMs, channelId, word32,
                note == null ? "" : note);
    }

    /** 取某材料某版本的原始字，按原位序列排序（原位序列原样保留，不做重排）。 */
    public List<Rows.RawWord> rawWords(String materialId, int revisionSeq) {
        List<Rows.RawWord> out = new ArrayList<>();
        query(this::readRawWord, out,
                "SELECT * FROM raw_word WHERE material_id=? AND revision_seq=? ORDER BY seq, id",
                materialId, revisionSeq);
        return out;
    }

    public Rows.RawWord rawWordBySid(String sid) {
        List<Rows.RawWord> out = new ArrayList<>();
        query(this::readRawWord, out, "SELECT * FROM raw_word WHERE sid=?", sid);
        return out.isEmpty() ? null : out.get(0);
    }

    private Rows.RawWord readRawWord(ResultSet rs) throws SQLException {
        return new Rows.RawWord(rs.getLong("id"), rs.getString("sid"),
                rs.getString("material_id"), rs.getInt("revision_seq"),
                rs.getString("word_id"), rs.getInt("seq"), rs.getDouble("recv_time_ms"),
                rs.getString("channel_id"), rs.getInt("word32"), rs.getString("note"));
    }

    // ---------- dictionary ----------
    public void addDictVersion(String version, String note, String supersedes) {
        exec("INSERT INTO dict_version(version,note,supersedes) VALUES(?,?,?)",
                version, note, supersedes);
    }

    public void addDictEntry(String sid, String version, int labelCode, Integer sdi,
                             String encoding, String specJson, String name) {
        exec("INSERT INTO dict_entry(sid,dict_version,label_code,sdi,encoding,spec_json,name) "
                        + "VALUES(?,?,?,?,?,?,?)",
                sid, version, labelCode, sdi, encoding, specJson, name);
    }

    public record DictEntryRow(String sid, String dictVersion, int labelCode, Integer sdi,
                               String encoding, String specJson, String name) {
    }

    public List<DictEntryRow> dictEntries(String version) {
        List<DictEntryRow> out = new ArrayList<>();
        query(rs -> new DictEntryRow(rs.getString("sid"), rs.getString("dict_version"),
                        rs.getInt("label_code"),
                        rs.getObject("sdi") == null ? null : rs.getInt("sdi"),
                        rs.getString("encoding"), rs.getString("spec_json"),
                        rs.getString("name")), out,
                "SELECT * FROM dict_entry WHERE dict_version=? ORDER BY label_code, sdi", version);
        return out;
    }

    public List<String> dictVersions() {
        List<String> out = new ArrayList<>();
        query(rs -> rs.getString("version"), out, "SELECT version FROM dict_version ORDER BY version");
        return out;
    }

    // ---------- branch / event ----------
    public void addBranch(String id, String name, String parentBranchId, Integer parentEventSeq,
                          int materialSeq, String dictVersion, String note) {
        exec("INSERT INTO branch(id,name,parent_branch_id,parent_event_seq,material_seq,"
                        + "dict_version,note) VALUES(?,?,?,?,?,?,?)",
                id, name, parentBranchId, parentEventSeq, materialSeq, dictVersion,
                note == null ? "" : note);
    }

    public Rows.Branch branch(String id) {
        List<Rows.Branch> out = new ArrayList<>();
        query(rs -> new Rows.Branch(rs.getString("id"), rs.getString("name"),
                        rs.getString("parent_branch_id"),
                        rs.getObject("parent_event_seq") == null ? null
                                : String.valueOf(rs.getInt("parent_event_seq")),
                        rs.getInt("material_seq"), rs.getString("dict_version"),
                        rs.getString("note")), out, "SELECT * FROM branch WHERE id=?", id);
        return out.isEmpty() ? null : out.get(0);
    }

    public List<Rows.Branch> branches() {
        List<Rows.Branch> out = new ArrayList<>();
        query(rs -> new Rows.Branch(rs.getString("id"), rs.getString("name"),
                        rs.getString("parent_branch_id"),
                        rs.getObject("parent_event_seq") == null ? null
                                : String.valueOf(rs.getInt("parent_event_seq")),
                        rs.getInt("material_seq"), rs.getString("dict_version"),
                        rs.getString("note")), out, "SELECT * FROM branch ORDER BY id");
        return out;
    }

    public void addEvent(String sid, String branchId, int seq, String type, String targetSid,
                         String payloadJson, String actor, double tsMs, String parentEventSid,
                         String basisLabel) {
        exec("INSERT INTO event(sid,branch_id,seq,type,target_sid,payload_json,actor,ts_ms,"
                        + "parent_event_sid,basis_label) VALUES(?,?,?,?,?,?,?,?,?,?)",
                sid, branchId, seq, type, targetSid, payloadJson, actor, tsMs,
                parentEventSid, basisLabel);
    }

    public List<Rows.Event> events(String branchId) {
        List<Rows.Event> out = new ArrayList<>();
        query(this::readEvent, out, "SELECT * FROM event WHERE branch_id=? ORDER BY seq", branchId);
        return out;
    }

    public Rows.Event eventAtSeq(String branchId, int seq) {
        List<Rows.Event> out = new ArrayList<>();
        query(this::readEvent, out, "SELECT * FROM event WHERE branch_id=? AND seq=?", branchId, seq);
        return out.isEmpty() ? null : out.get(0);
    }

    public int nextEventSeq(String branchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(seq),0)+1 FROM event WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Rows.Event readEvent(ResultSet rs) throws SQLException {
        return new Rows.Event(rs.getLong("id"), rs.getString("sid"), rs.getString("branch_id"),
                rs.getInt("seq"), rs.getString("type"), rs.getString("target_sid"),
                rs.getString("payload_json"), rs.getString("actor"), rs.getDouble("ts_ms"),
                rs.getString("parent_event_sid"), rs.getString("basis_label"));
    }

    public boolean isEmpty() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM raw_word")) {
            rs.next();
            return rs.getInt(1) == 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- generic helpers ----------
    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("sql failed: " + sql, e);
        }
    }

    private <T> void query(Mapper<T> mapper, List<T> sink, String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sink.add(mapper.map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + sql, e);
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
