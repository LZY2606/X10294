package av.a429.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SQLite store with explicit, ordered schema migrations. Migration 1 is the
 * initial schema; migration 2 adds coordinate-basis and time-unit columns so
 * domain quantities keep their unit / frame of reference.
 */
public final class Database implements AutoCloseable {

    private final Connection conn;

    public Database(String path) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA journal_mode=WAL");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    private int schemaVersion() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version(version INT NOT NULL)");
            var rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version");
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void migrate() throws SQLException {
        int v = schemaVersion();
        List<String[]> migrations = List.of(
                // 1: initial schema
                new String[]{
                    "CREATE TABLE IF NOT EXISTS imports("
                        + "id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL,"
                        + "dict_version TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS words("
                        + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL REFERENCES imports(id),"
                        + "seq INT NOT NULL, rx_time_raw TEXT NOT NULL, channel TEXT NOT NULL,"
                        + "raw_hex TEXT NOT NULL, dict_version TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS word_patches("
                        + "word_id TEXT NOT NULL REFERENCES words(id), event_seq INT NOT NULL,"
                        + "raw_hex TEXT NOT NULL, PRIMARY KEY(word_id, event_seq))",
                    "CREATE TABLE IF NOT EXISTS dict_entries("
                        + "entry_id TEXT PRIMARY KEY, dict_version TEXT NOT NULL, label_octal TEXT NOT NULL,"
                        + "encoding TEXT NOT NULL, unit TEXT, scale REAL NOT NULL, description TEXT)",
                    "CREATE TABLE IF NOT EXISTS branches("
                        + "id TEXT PRIMARY KEY, name TEXT NOT NULL, base_event_seq INT NOT NULL,"
                        + "dict_version TEXT NOT NULL, created_at TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS events("
                        + "seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL,"
                        + "branch_id TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL,"
                        + "created_at TEXT NOT NULL)"
                },
                // 2: units and coordinate basis on every domain quantity
                new String[]{
                    "ALTER TABLE words ADD COLUMN rx_time_unit TEXT NOT NULL DEFAULT 'ms'",
                    "ALTER TABLE dict_entries ADD COLUMN coord_basis TEXT NOT NULL DEFAULT 'unspecified'"
                }
        );
        for (int i = v; i < migrations.size(); i++) {
            try (Statement st = conn.createStatement()) {
                conn.setAutoCommit(false);
                for (String sql : migrations.get(i)) {
                    st.execute(sql);
                }
                st.execute("INSERT INTO schema_version(version) VALUES(" + (i + 1) + ")");
                conn.commit();
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(true);
                throw e;
            }
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
