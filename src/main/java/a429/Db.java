package a429;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class Db {
    public static final int SCHEMA_VERSION = 2;

    public static Connection open(String path) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        migrate(c, SCHEMA_VERSION);
        return c;
    }

    public static int currentVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            try (ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Apply migrations up to targetVersion. Exposed for migration tests. */
    public static void migrate(Connection c, int targetVersion) throws SQLException {
        int v = currentVersion(c);
        if (v < 1 && targetVersion >= 1) { mig1(c); record(c, 1); }
        if (v < 2 && targetVersion >= 2) { mig2(c); record(c, 2); }
    }

    private static void record(Connection c, int v) throws SQLException {
        try (var ps = c.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES(?,?)")) {
            ps.setInt(1, v);
            ps.setString(2, java.time.Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private static void mig1(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE imports(
                  id TEXT PRIMARY KEY,
                  source TEXT NOT NULL,
                  created_at TEXT NOT NULL)""");
            s.execute("""
                CREATE TABLE dictionaries(
                  version TEXT PRIMARY KEY,
                  json TEXT NOT NULL)""");
            s.execute("""
                CREATE TABLE words(
                  id TEXT PRIMARY KEY,
                  import_id TEXT NOT NULL REFERENCES imports(id),
                  seq INTEGER NOT NULL,
                  rx_time_ns INTEGER NOT NULL,
                  channel TEXT NOT NULL,
                  raw INTEGER NOT NULL,
                  label_byte INTEGER NOT NULL,
                  label_octal TEXT NOT NULL,
                  sdi INTEGER NOT NULL,
                  ssm INTEGER NOT NULL,
                  data_field INTEGER NOT NULL,
                  parity_ok INTEGER NOT NULL,
                  dict_version TEXT NOT NULL,
                  supersedes TEXT)""");
            s.execute("""
                CREATE TABLE branches(
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  dict_version TEXT NOT NULL,
                  parent_id TEXT,
                  created_seq INTEGER NOT NULL)""");
            s.execute("""
                CREATE TABLE events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  id TEXT NOT NULL,
                  branch_id TEXT,
                  type TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  created_at TEXT NOT NULL)""");
            s.execute("""
                CREATE TABLE conclusions(
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  target_id TEXT NOT NULL,
                  value TEXT NOT NULL,
                  deps TEXT NOT NULL,
                  dict_version TEXT NOT NULL,
                  status TEXT NOT NULL,
                  created_seq INTEGER NOT NULL,
                  superseded_seq INTEGER)""");
        }
    }

    /** v2: review-facing additions — per-word diagnostics column and conflict records. */
    private static void mig2(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE words ADD COLUMN diagnostics TEXT");
            s.execute("""
                CREATE TABLE conflicts(
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  target_id TEXT NOT NULL,
                  base_version INTEGER NOT NULL,
                  attempted TEXT NOT NULL,
                  existing TEXT NOT NULL,
                  created_seq INTEGER NOT NULL)""");
        }
    }
}
