package avio.a429.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** SQLite handle with explicit, ordered schema migrations. */
public final class Db implements AutoCloseable {
    public static final List<String[]> MIGRATIONS = List.of(
            new String[]{"1", """
                CREATE TABLE import_batch(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE raw_word(
                  id TEXT PRIMARY KEY, batch_id TEXT NOT NULL REFERENCES import_batch(id),
                  seq INTEGER NOT NULL, rx_time TEXT NOT NULL, rx_ns INTEGER NOT NULL,
                  channel TEXT NOT NULL, raw_hex TEXT NOT NULL, declared_parity TEXT,
                  label_text TEXT, sdi INTEGER, ssm INTEGER, data_hex TEXT,
                  dict_version TEXT NOT NULL, supersedes TEXT);
                CREATE TABLE dictionary(version TEXT PRIMARY KEY, body TEXT NOT NULL);
                CREATE TABLE branch(id TEXT PRIMARY KEY, name TEXT NOT NULL, base_branch TEXT, created_at TEXT NOT NULL);
                CREATE TABLE event(id TEXT PRIMARY KEY, seq INTEGER NOT NULL, branch_id TEXT, kind TEXT NOT NULL,
                  actor TEXT, payload TEXT NOT NULL, dict_version TEXT, rule_version TEXT, created_at TEXT NOT NULL);
                CREATE TABLE derived_value(id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, word_id TEXT,
                  kind TEXT NOT NULL, num REAL, text TEXT, unit TEXT, frame TEXT, diag TEXT,
                  dict_version TEXT NOT NULL, rule_version TEXT NOT NULL, payload TEXT NOT NULL);
                CREATE TABLE candidate(id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, kind TEXT NOT NULL,
                  subject_word_id TEXT, payload TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open',
                  version INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE adjudication(id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL, branch_id TEXT NOT NULL,
                  action TEXT NOT NULL, note TEXT, actor TEXT, event_seq INTEGER NOT NULL, created_at TEXT NOT NULL);
                """},
            new String[]{"2", """
                CREATE TABLE dependency(derived_id TEXT NOT NULL, depends_on TEXT NOT NULL, kind TEXT NOT NULL);
                ALTER TABLE derived_value ADD COLUMN stale INTEGER NOT NULL DEFAULT 0;
                """},
            new String[]{"3", """
                ALTER TABLE candidate ADD COLUMN decided_by TEXT;
                ALTER TABLE adjudication ADD COLUMN prev_status TEXT;
                ALTER TABLE adjudication ADD COLUMN new_status TEXT;
                ALTER TABLE adjudication ADD COLUMN undo_of TEXT;
                CREATE INDEX idx_event_seq ON event(seq);
                CREATE INDEX idx_derived_branch ON derived_value(branch_id, dict_version, rule_version);
                """}
    );

    private final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA journal_mode = WAL");
            }
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open db " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    public int schemaVersion() {
        try (Statement s = conn.createStatement();
             var rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    public void migrate() {
        try {
            conn.setAutoCommit(false);
            int current = schemaVersionUnchecked();
            for (String[] m : MIGRATIONS) {
                int v = Integer.parseInt(m[0]);
                if (v <= current) continue;
                try (Statement s = conn.createStatement()) {
                    for (String stmt : m[1].split(";")) {
                        if (!stmt.isBlank()) s.execute(stmt);
                    }
                    s.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (" + v
                            + ", datetime('now'))");
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException("migration failed", e);
        }
    }

    private int schemaVersionUnchecked() {
        try (Statement s = conn.createStatement();
             var rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            // schema_migrations does not exist yet: bootstrap it inside the same tx
            try (Statement s2 = conn.createStatement()) {
                s2.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            } catch (SQLException e2) {
                throw new RuntimeException(e2);
            }
            return 0;
        }
    }

    /** Run fn inside a transaction; any exception rolls the whole change back. */
    public <T> T tx(java.util.concurrent.Callable<T> fn) {
        try {
            conn.setAutoCommit(false);
            T out = fn.call();
            conn.commit();
            return out;
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                e.addSuppressed(re);
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {}
    }
}
