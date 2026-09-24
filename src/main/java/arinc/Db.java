package arinc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** SQLite access plus explicit, ordered schema migrations. */
public final class Db implements AutoCloseable {

    private final Connection conn;

    public Db(String path) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
            }
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("open db " + path, e);
        }
    }

    public Connection conn() { return conn; }

    /** Ordered migrations; each runs once, recorded in schema_migrations. */
    static final List<String[]> MIGRATIONS = List.of(
            {"1", """
                CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE imports (
                  id TEXT PRIMARY KEY, name TEXT, case_id TEXT,
                  dict_version TEXT, status TEXT, created_at TEXT);
                CREATE TABLE raw_words (
                  id TEXT PRIMARY KEY, import_id TEXT NOT NULL, seq INTEGER NOT NULL,
                  rx_time_ns INTEGER NOT NULL, channel INTEGER NOT NULL,
                  raw_hex TEXT NOT NULL, parity_ok INTEGER NOT NULL,
                  label_octal TEXT NOT NULL, sdi INTEGER NOT NULL, ssm INTEGER NOT NULL,
                  data_field INTEGER NOT NULL, dict_version TEXT NOT NULL,
                  note TEXT DEFAULT '');
                CREATE TABLE branches (
                  id TEXT PRIMARY KEY, import_id TEXT NOT NULL, name TEXT NOT NULL,
                  parent_id TEXT, created_at TEXT);
                CREATE TABLE interpretations (
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, word_id TEXT NOT NULL,
                  candidate_kind TEXT NOT NULL, encoding TEXT NOT NULL,
                  value REAL, value_text TEXT, unit TEXT NOT NULL DEFAULT '',
                  status TEXT NOT NULL, diagnostic TEXT NOT NULL DEFAULT '',
                  dict_version TEXT NOT NULL, stale INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE rate_segments (
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,
                  start_seq INTEGER NOT NULL, end_seq INTEGER NOT NULL,
                  rate_hz INTEGER NOT NULL, rank INTEGER NOT NULL,
                  status TEXT NOT NULL, rationale TEXT NOT NULL DEFAULT '',
                  stale INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE decisions (
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,
                  target_type TEXT NOT NULL, target_id TEXT NOT NULL,
                  action TEXT NOT NULL, actor TEXT NOT NULL,
                  base_rev INTEGER NOT NULL, created_at TEXT, reverted_by TEXT);
                CREATE TABLE events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL,
                  type TEXT NOT NULL, branch_id TEXT, payload TEXT NOT NULL);
            """},
            {"2", """
                CREATE TABLE dependencies (
                  id TEXT PRIMARY KEY, from_type TEXT NOT NULL, from_id TEXT NOT NULL,
                  to_type TEXT NOT NULL, to_id TEXT NOT NULL);
                CREATE TABLE source_fixes (
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, word_id TEXT NOT NULL,
                  new_hex TEXT NOT NULL, actor TEXT NOT NULL, created_at TEXT);
            """},
            {"3", """
                ALTER TABLE branches ADD COLUMN rev INTEGER NOT NULL DEFAULT 0;
                CREATE INDEX idx_events_branch ON events(branch_id, id);
                CREATE INDEX idx_interp_branch ON interpretations(branch_id, word_id);
                CREATE INDEX idx_words_import ON raw_words(import_id, seq);
            """}
    );

    public void migrate() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT)");
            int current = 0;
            var rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations");
            if (rs.next()) current = rs.getInt(1);
            for (String[] m : MIGRATIONS) {
                int v = Integer.parseInt(m[0]);
                if (v > current) {
                    for (String stmt : m[1].split(";")) {
                        String sql = stmt.trim();
                        if (!sql.isEmpty()) s.execute(sql);
                    }
                    s.execute("INSERT INTO schema_migrations (version, applied_at) VALUES (" + v + ", datetime('now'))");
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Run inside a transaction; any failure rolls back the whole change. */
    public <T> T tx(SqlWork<T> work) {
        try {
            conn.setAutoCommit(false);
            T r = work.run(conn);
            conn.commit();
            return r;
        } catch (Exception e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public interface SqlWork<T> { T run(Connection c) throws SQLException; }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
