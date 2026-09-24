package a429.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite connection holder with explicit schema migration history. */
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.setAutoCommit(false);
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_meta (
                    name TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");
        }
        int current = readVersion();
        if (current == 0) {
            // ---- v1: the original immutable event journal ----
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE events (
                        seq INTEGER PRIMARY KEY,
                        event_id TEXT NOT NULL UNIQUE,
                        branch TEXT NOT NULL,
                        seq_in_branch INTEGER NOT NULL,
                        parent_seq INTEGER,
                        type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        author TEXT NOT NULL,
                        created_at_micros INTEGER NOT NULL,
                        case_pack_id TEXT,
                        case_pack_version TEXT
                    )""");
                st.executeUpdate("""
                    CREATE TABLE branch_tips (
                        branch TEXT PRIMARY KEY,
                        tip_seq INTEGER NOT NULL
                    )""");
                st.executeUpdate("""
                    CREATE TABLE event_refs (
                        seq INTEGER NOT NULL,
                        ref_kind TEXT NOT NULL,
                        ref_value TEXT NOT NULL
                    )""");
                st.executeUpdate("CREATE INDEX idx_events_branch ON events(branch, seq_in_branch)");
                st.executeUpdate("CREATE INDEX idx_event_refs ON event_refs(seq, ref_kind)");
            }
            current = 1;
        }
        if (current == 1) {
            // ---- v2: machine-readable rationale / invalidation ledger ----
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE rationale_events (
                        seq INTEGER PRIMARY KEY,
                        event_id TEXT NOT NULL UNIQUE,
                        rule_code TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        branch TEXT NOT NULL,
                        basis_event_seq INTEGER,
                        payload TEXT NOT NULL
                    )""");
                st.executeUpdate("""
                    CREATE TABLE invalidations (
                        seq INTEGER NOT NULL,
                        artifact_id TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        PRIMARY KEY (seq, artifact_id)
                    )""");
                st.executeUpdate("CREATE INDEX idx_invalidations_art ON invalidations(artifact_id)");
            }
            current = 2;
        }
        writeVersion(current);
        conn.commit();
    }

    private int readVersion() throws SQLException {
        try (var rs = conn.createStatement().executeQuery(
                "SELECT value FROM schema_meta WHERE name = 'schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private void writeVersion(int v) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO schema_meta(name, value) VALUES('schema_version', ?)
                ON CONFLICT(name) DO UPDATE SET value = excluded.value""")) {
            ps.setString(1, String.valueOf(v));
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) { }
    }
}
