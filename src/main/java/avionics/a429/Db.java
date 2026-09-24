package avionics.a429;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite access plus explicit, ordered schema migrations. */
public final class Db implements AutoCloseable {

    /** Ordered migrations; index+1 is the schema version they produce. */
    private static final String[] MIGRATIONS = {
        // v1: core provenance-preserving schema
        """
        CREATE TABLE imports (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE channels (
            id TEXT PRIMARY KEY,
            speed TEXT NOT NULL,
            bit_order TEXT NOT NULL DEFAULT 'normal'
        );
        CREATE TABLE dictionaries (
            version TEXT PRIMARY KEY,
            json TEXT NOT NULL
        );
        CREATE TABLE words (
            id TEXT PRIMARY KEY,
            import_id TEXT NOT NULL REFERENCES imports(id),
            seq INTEGER NOT NULL,
            rx_time TEXT NOT NULL,
            channel TEXT NOT NULL REFERENCES channels(id),
            raw32 TEXT NOT NULL,
            dict_version TEXT NOT NULL REFERENCES dictionaries(version)
        );
        CREATE TABLE word_sources (
            word_id TEXT NOT NULL REFERENCES words(id),
            revision INTEGER NOT NULL,
            raw32 TEXT NOT NULL,
            event_seq INTEGER NOT NULL,
            PRIMARY KEY (word_id, revision)
        );
        CREATE TABLE events (
            id TEXT PRIMARY KEY,
            seq INTEGER NOT NULL UNIQUE,
            branch_id TEXT NOT NULL,
            type TEXT NOT NULL,
            payload TEXT NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE branches (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            parent_id TEXT,
            base_event_seq INTEGER NOT NULL,
            head_seq INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'open'
        );
        CREATE TABLE rulings (
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL REFERENCES branches(id),
            target_id TEXT NOT NULL,
            decision TEXT NOT NULL,
            params TEXT NOT NULL,
            base_head INTEGER NOT NULL,
            prev_head INTEGER NOT NULL,
            new_head INTEGER NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );
        CREATE TABLE derived_values (
            id TEXT PRIMARY KEY,
            word_id TEXT NOT NULL REFERENCES words(id),
            branch_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            value TEXT,
            unit TEXT,
            status TEXT NOT NULL,
            diagnostic TEXT,
            dict_version TEXT NOT NULL,
            created_event_seq INTEGER NOT NULL,
            invalidated_event_seq INTEGER
        );
        CREATE TABLE segment_candidates (
            id TEXT PRIMARY KEY,
            segment_id TEXT NOT NULL,
            channel TEXT NOT NULL,
            label_octal TEXT NOT NULL,
            kind TEXT NOT NULL,
            detail TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'open',
            branch_id TEXT NOT NULL,
            created_event_seq INTEGER NOT NULL
        );
        """,
        // v2: engineering values carry an explicit coordinate/unit reference
        """
        ALTER TABLE derived_values ADD COLUMN unit_ref TEXT;
        ALTER TABLE words ADD COLUMN note TEXT;
        """,
    };

    private final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("open db " + path, e);
        }
    }

    public static Db inMemory() {
        return open(":memory:");
    }

    public int schemaVersion() {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            try (ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Apply pending migrations in order, each in its own transaction. */
    public void migrate() {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        int current = schemaVersion();
        for (int v = current + 1; v <= MIGRATIONS.length; v++) {
            applyMigration(v, MIGRATIONS[v - 1]);
        }
    }

    /** Exposed for migration tests: apply exactly one migration version. */
    public void applyMigration(int version, String sql) {
        boolean previousAutoCommit;
        try {
            previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            for (String stmt : sql.split(";\\s*\\n")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try (Statement s = conn.createStatement()) {
                        s.execute(trimmed);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setString(2, java.time.Instant.now().toString());
                ps.executeUpdate();
            }
            conn.commit();
            conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            throw new RuntimeException("migration v" + version + " failed", e);
        }
    }

    public static int migrationCount() {
        return MIGRATIONS.length;
    }

    public static String migrationSql(int version) {
        return MIGRATIONS[version - 1];
    }

    public Connection conn() {
        return conn;
    }

    public List<String> tableNames() {
        List<String> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
