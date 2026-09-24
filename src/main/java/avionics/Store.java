package avionics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite persistence. Source (raw words, timestamps, channel config) and
 * interpretation (events, branches, adjudications) are stored separately.
 * Nothing is cleaned irreversibly at import; every judgement is an event.
 */
public final class Store implements AutoCloseable {

    private final Connection conn;

    public Store(String jdbcUrl) {
        try {
            this.conn = DriverManager.getConnection(jdbcUrl);
            conn.setAutoCommit(false);
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Store inMemory() {
        return new Store("jdbc:sqlite::memory:");
    }

    public static Store file(String path) {
        return new Store("jdbc:sqlite:" + path);
    }

    public Connection conn() {
        return conn;
    }

    // ---------------------------------------------------------------- schema

    private int schemaVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
            if (!rs.next()) return 0;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Migrations are applied in order; each is one irreversible schema step, data-preserving. */
    private void migrate() throws SQLException {
        int v = schemaVersion();
        if (v < 1) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE schema_version(version INTEGER NOT NULL)""");
                st.execute("INSERT INTO schema_version(version) VALUES (1)");
                st.execute("""
                    CREATE TABLE imports(
                        import_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        dict_version TEXT NOT NULL,
                        channel_config TEXT NOT NULL)""");
                st.execute("""
                    CREATE TABLE words(
                        word_id TEXT PRIMARY KEY,
                        import_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        recv_ns INTEGER NOT NULL,
                        channel TEXT NOT NULL,
                        raw_hex TEXT NOT NULL,
                        parity_ok INTEGER NOT NULL,
                        label_oct TEXT NOT NULL,
                        sdi INTEGER NOT NULL,
                        ssm INTEGER NOT NULL,
                        data_field INTEGER NOT NULL,
                        dict_version TEXT NOT NULL)""");
                st.execute("""
                    CREATE TABLE events(
                        event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        branch_id TEXT NOT NULL,
                        parent_id INTEGER,
                        kind TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        actor TEXT NOT NULL,
                        created_at TEXT NOT NULL)""");
                st.execute("""
                    CREATE TABLE branches(
                        branch_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        head_event INTEGER NOT NULL,
                        version INTEGER NOT NULL)""");
            }
            v = 1;
        }
        if (v < 2) {
            // v2: diagnostics stay with the word; rate segment candidates get their own table.
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE words ADD COLUMN diagnostic TEXT");
                st.execute("""
                    CREATE TABLE rate_segments(
                        segment_id TEXT PRIMARY KEY,
                        branch_id TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        start_seq INTEGER NOT NULL,
                        end_seq INTEGER NOT NULL,
                        candidate_rates TEXT NOT NULL,
                        accepted_rate INTEGER,
                        status TEXT NOT NULL)""");
                st.execute("INSERT INTO schema_version(version) VALUES (2)");
            }
            v = 2;
        }
        conn.commit();
    }

    // ---------------------------------------------------------------- helpers

    public void execute(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException(e);
        }
    }

    /** Runs a unit of work atomically: a failed import rolls back the whole change. */
    public void tx(Runnable body) {
        try {
            body.run();
            conn.commit();
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setObject(i + 1, null);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else if (a instanceof Long n) ps.setLong(i + 1, n);
            else ps.setString(i + 1, a.toString());
        }
    }

    public List<String[]> query(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                List<String[]> rows = new ArrayList<>();
                while (rs.next()) {
                    String[] row = new String[cols];
                    for (int i = 0; i < cols; i++) row[i] = rs.getString(i + 1);
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<String[]> queryOne(String sql, Object... args) {
        List<String[]> rows = query(sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
