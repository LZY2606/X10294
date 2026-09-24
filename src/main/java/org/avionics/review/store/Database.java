package org.avionics.review.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite lifecycle and schema migration.
 *
 * <p>{@code migrateV1ToV2} is exercised by the state-migration test: a
 * database created at schema v1 (no invalidation table, no rule_version
 * column) is upgraded in place without losing events.</p>
 */
public class Database implements AutoCloseable {

    public static final int CURRENT_SCHEMA = 2;

    private final Connection conn;

    public Database(String path) {
        try {
            String url = "jdbc:sqlite:" + path;
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            conn.commit();
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public Connection connection() {
        return conn;
    }

    private void migrate() throws SQLException {
        int version = readVersion();
        if (version == 0) {
            createV1();
            version = 1;
        }
        if (version == 1) {
            migrateV1ToV2();
            version = 2;
        }
        if (version != CURRENT_SCHEMA) {
            throw new IllegalStateException("unsupported schema version " + version);
        }
    }

    private int readVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void createV1() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE branches ("
                    + "name TEXT PRIMARY KEY,"
                    + "parent_branch TEXT,"
                    + "fork_after_event TEXT,"
                    + "created_at TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE events ("
                    + "event_id TEXT PRIMARY KEY,"
                    + "seq INTEGER NOT NULL,"
                    + "branch TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "payload TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL,"
                    + "superseded_by TEXT)");
            st.executeUpdate("CREATE TABLE invalidations ("
                    + "event_id TEXT NOT NULL,"
                    + "scope_type TEXT NOT NULL,"
                    + "sid TEXT NOT NULL,"
                    + "reason TEXT NOT NULL)");
            st.executeUpdate("PRAGMA user_version=1");
        }
        conn.commit();
    }

    private void migrateV1ToV2() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE events ADD COLUMN rule_version TEXT");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_branch "
                    + "ON events(branch, seq)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_invalidation_event "
                    + "ON invalidations(event_id)");
            st.executeUpdate("PRAGMA user_version=2");
        }
        conn.commit();
    }

    public void wipe() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM invalidations");
            st.executeUpdate("DELETE FROM events");
            st.executeUpdate("DELETE FROM branches");
        }
    }

    public PreparedStatement prepare(String sql) throws SQLException {
        return conn.prepareStatement(sql);
    }

    public void commit() throws SQLException {
        conn.commit();
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
