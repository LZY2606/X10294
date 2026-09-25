package com.avionics.a429.store;

import com.avionics.a429.core.Models.SourceItem;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 存储。来源（source_item）与解释（event / view_cache）分开保存。
 * schema 版本经 user_version 迁移；导入与事件写入都在事务内完成，失败整体回滚。
 */
public final class Store implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    public record EventRow(String eventId, long seq, String branchId, String ts,
                           String kind, String payload, String invalidation) {}
    public record BranchRow(String branchId, String name, String parentBranch,
                            String parentEvent, String created) {}

    private Store(Connection conn) { this.conn = conn; }

    public static Store open(Path db) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            Store store = new Store(c);
            store.migrate();
            return store;
        } catch (SQLException e) {
            throw new RuntimeException("打开数据库失败: " + db, e);
        }
    }

    public static Store inMemory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Store store = new Store(c);
            store.migrate();
            return store;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int userVersion() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void migrate() throws SQLException {
        int v = userVersion();
        if (v < 1) {
            try (Statement s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS meta(
                      key TEXT PRIMARY KEY, value TEXT NOT NULL)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS source_item(
                      item_id TEXT PRIMARY KEY,
                      import_id TEXT NOT NULL,
                      seq INTEGER NOT NULL,
                      rx_time TEXT NOT NULL,
                      rx_nanos INTEGER,
                      channel TEXT NOT NULL,
                      raw_text TEXT NOT NULL,
                      raw_bits INTEGER,
                      parse_diag TEXT,
                      source_ref TEXT NOT NULL)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS event(
                      event_id TEXT PRIMARY KEY,
                      seq INTEGER NOT NULL,
                      branch_id TEXT NOT NULL,
                      ts TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      payload TEXT NOT NULL)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS branch(
                      branch_id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      parent_branch TEXT,
                      parent_event TEXT,
                      created TEXT NOT NULL)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS view_cache(
                      event_id TEXT PRIMARY KEY,
                      view_json TEXT NOT NULL)""");
                s.execute("PRAGMA user_version = 1");
            }
        }
        if (v < 2) {
            // v2：事件携带机器可读的失效范围；显式依赖边表
            try (Statement s = conn.createStatement()) {
                s.execute("ALTER TABLE event ADD COLUMN invalidation TEXT");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS dep(
                      event_id TEXT NOT NULL,
                      uid TEXT NOT NULL,
                      depends_on TEXT NOT NULL,
                      PRIMARY KEY(event_id, uid, depends_on))""");
                s.execute("PRAGMA user_version = 2");
            }
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    /** 在单个事务中执行；异常即回滚，不留半份记录。 */
    public void inTransaction(Runnable body) {
        try {
            conn.setAutoCommit(false);
            body.run();
            conn.commit();
        } catch (RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public void insertItem(SourceItem it) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source_item VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, it.itemId());
            ps.setString(2, it.importId());
            ps.setInt(3, it.seq());
            ps.setString(4, it.rxTime());
            if (it.rxNanos() == null) ps.setNull(5, java.sql.Types.INTEGER);
            else ps.setLong(5, it.rxNanos());
            ps.setString(6, it.channel());
            ps.setString(7, it.rawText());
            if (it.rawBits() == null) ps.setNull(8, java.sql.Types.INTEGER);
            else ps.setLong(8, it.rawBits());
            if (it.parseDiag() == null) ps.setNull(9, java.sql.Types.VARCHAR);
            else ps.setString(9, it.parseDiag());
            ps.setString(10, it.sourceRef());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入源数据项失败 " + it.itemId() + ": " + e.getMessage(), e);
        }
    }

    public List<SourceItem> allItems() {
        List<SourceItem> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM source_item ORDER BY rowid")) {
            while (rs.next()) {
                long rxNanos = rs.getLong("rx_nanos");
                long rawBits = rs.getLong("raw_bits");
                out.add(new SourceItem(
                        rs.getString("item_id"), rs.getString("import_id"), rs.getInt("seq"),
                        rs.getString("rx_time"), rs.wasNull() ? null : rxNanos,
                        rs.getString("channel"), rs.getString("raw_text"),
                        rs.getObject("raw_bits") == null ? null : rawBits,
                        rs.getString("parse_diag"), rs.getString("source_ref")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public SourceItem itemById(String itemId) {
        return allItems().stream().filter(i -> i.itemId().equals(itemId)).findFirst().orElse(null);
    }

    public long nextEventSeq() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM event")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void insertEvent(EventRow ev) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO event(event_id,seq,branch_id,ts,kind,payload,invalidation) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, ev.eventId());
            ps.setLong(2, ev.seq());
            ps.setString(3, ev.branchId());
            ps.setString(4, ev.ts());
            ps.setString(5, ev.kind());
            ps.setString(6, ev.payload());
            if (ev.invalidation() == null) ps.setNull(7, java.sql.Types.VARCHAR);
            else ps.setString(7, ev.invalidation());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("写入事件失败: " + e.getMessage(), e);
        }
    }

    public List<EventRow> eventsOfBranch(String branchId) {
        List<EventRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM event WHERE branch_id=? ORDER BY seq")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(readEvent(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<EventRow> allEvents() {
        List<EventRow> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM event ORDER BY seq")) {
            while (rs.next()) out.add(readEvent(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private EventRow readEvent(ResultSet rs) throws SQLException {
        return new EventRow(rs.getString("event_id"), rs.getLong("seq"),
                rs.getString("branch_id"), rs.getString("ts"), rs.getString("kind"),
                rs.getString("payload"), rs.getString("invalidation"));
    }

    public void insertBranch(BranchRow b) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branch VALUES(?,?,?,?,?)")) {
            ps.setString(1, b.branchId());
            ps.setString(2, b.name());
            if (b.parentBranch() == null) ps.setNull(3, java.sql.Types.VARCHAR);
            else ps.setString(3, b.parentBranch());
            if (b.parentEvent() == null) ps.setNull(4, java.sql.Types.VARCHAR);
            else ps.setString(4, b.parentEvent());
            ps.setString(5, b.created());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<BranchRow> branches() {
        List<BranchRow> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM branch ORDER BY rowid")) {
            while (rs.next()) {
                out.add(new BranchRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public BranchRow branch(String branchId) {
        return branches().stream().filter(b -> b.branchId().equals(branchId)).findFirst().orElse(null);
    }

    public String cachedView(String eventId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT view_json FROM view_cache WHERE event_id=?")) {
            ps.setString(1, eventId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void putCachedView(String eventId, String viewJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO view_cache VALUES(?,?)")) {
            ps.setString(1, eventId);
            ps.setString(2, viewJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void putDeps(String eventId, String uid, List<String> deps) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO dep VALUES(?,?,?)")) {
            for (String d : deps) {
                ps.setString(1, eventId);
                ps.setString(2, uid);
                ps.setString(3, d);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String[]> depsOf(String eventId) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uid, depends_on FROM dep WHERE event_id=?")) {
            ps.setString(1, eventId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
