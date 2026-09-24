package a429.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite persistence with explicit schema migrations.
 *
 * v1: imports, dictionaries, words, branches, events, adjudications, word_amendments
 * v2: adds invalidations, conflicts and the events.snapshot column used for
 *     dependency-scoped invalidation and historical state restore.
 */
public final class Database implements AutoCloseable {

    public static final int CURRENT_VERSION = 2;

    private final String path;
    private Connection conn;

    public Database(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public synchronized Connection conn() {
        try {
            if (conn == null || conn.isClosed()) {
                conn = DriverManager.getConnection("jdbc:sqlite:" + path);
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                    st.execute("PRAGMA journal_mode = WAL");
                }
                migrate(conn);
            }
            return conn;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public int schemaVersion() {
        try (Statement st = conn().createStatement();
                ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Applies pending migrations in order. Existing rows are never rewritten. */
    public static void migrate(Connection c) throws SQLException {
        int version;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
        }
        if (version < 1) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS imports ("
                        + "id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL, status TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS dictionaries ("
                        + "version TEXT PRIMARY KEY, body TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS words ("
                        + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, seq INTEGER NOT NULL,"
                        + " rx_time_ns INTEGER NOT NULL, channel TEXT NOT NULL, raw_word INTEGER NOT NULL,"
                        + " parity_ok INTEGER NOT NULL, label_octal TEXT NOT NULL, sdi INTEGER NOT NULL,"
                        + " ssm INTEGER NOT NULL, data29 INTEGER NOT NULL, dict_version TEXT NOT NULL,"
                        + " note TEXT NOT NULL DEFAULT '')");
                st.execute("CREATE TABLE IF NOT EXISTS branches ("
                        + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, name TEXT NOT NULL,"
                        + " dict_version TEXT NOT NULL, params TEXT NOT NULL, parent_id TEXT,"
                        + " base_event_seq INTEGER NOT NULL, created_at TEXT NOT NULL,"
                        + " status TEXT NOT NULL DEFAULT 'active')");
                st.execute("CREATE TABLE IF NOT EXISTS events ("
                        + "seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, branch_id TEXT,"
                        + " import_id TEXT, type TEXT NOT NULL, payload TEXT NOT NULL,"
                        + " created_at TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS adjudications ("
                        + "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, event_seq INTEGER NOT NULL,"
                        + " target_type TEXT NOT NULL, target_id TEXT NOT NULL, decision TEXT NOT NULL,"
                        + " detail TEXT NOT NULL DEFAULT '', reverted INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE IF NOT EXISTS word_amendments ("
                        + "id TEXT PRIMARY KEY, word_id TEXT NOT NULL, raw_word INTEGER NOT NULL,"
                        + " reason TEXT NOT NULL, event_seq INTEGER NOT NULL, created_at TEXT NOT NULL)");
                st.execute("INSERT INTO schema_version(version) VALUES (1)");
            }
            version = 1;
        }
        if (version < 2) {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE events ADD COLUMN snapshot TEXT NOT NULL DEFAULT '{}'");
                st.execute("CREATE TABLE IF NOT EXISTS invalidations ("
                        + "id TEXT PRIMARY KEY, event_seq INTEGER NOT NULL, branch_id TEXT,"
                        + " scope TEXT NOT NULL, reason TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS conflicts ("
                        + "id TEXT PRIMARY KEY, branch_id TEXT, base_seq INTEGER NOT NULL,"
                        + " head_seq INTEGER NOT NULL, your_payload TEXT NOT NULL,"
                        + " their_events TEXT NOT NULL, created_at TEXT NOT NULL)");
                st.execute("INSERT INTO schema_version(version) VALUES (2)");
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
