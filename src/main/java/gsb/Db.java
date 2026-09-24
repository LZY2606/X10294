package gsb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;

/** SQLite 模式与状态迁移。当前版本 3。 */
public final class Db {
    private Db() {}

    public static final int SCHEMA_VERSION = 3;

    public static Connection open(Path path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            c.setAutoCommit(true);
            return c;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void migrate(Connection c) {
        migrateTo(c, SCHEMA_VERSION);
    }

    public static void migrateTo(Connection c, int target) {
        try {
            int v = userVersion(c);
            while (v < target) {
                v++;
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    switch (v) {
                        case 1 -> migration1(s);
                        case 2 -> migration2(s);
                        case 3 -> migration3(s);
                        default -> throw new IllegalStateException("unknown migration " + v);
                    }
                    s.execute("PRAGMA user_version=" + v);
                }
                c.commit();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            try { c.rollback(); c.setAutoCommit(true); } catch (SQLException ignored) {}
            throw new RuntimeException(e);
        }
    }

    static int userVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); var rs = s.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
        }
    }

    /** v1：导入、字典版本、原始字、事件。 */
    private static void migration1(Statement s) throws SQLException {
        s.execute("CREATE TABLE imports("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL, dict_version TEXT NOT NULL,"
                + " meta TEXT NOT NULL, created_at TEXT NOT NULL)");
        s.execute("CREATE TABLE dictionaries("
                + "import_id TEXT NOT NULL, version TEXT NOT NULL, json TEXT NOT NULL,"
                + " PRIMARY KEY(import_id, version))");
        s.execute("CREATE TABLE words("
                + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, seq INTEGER NOT NULL,"
                + " rx_ns INTEGER NOT NULL, channel TEXT NOT NULL, raw32 INTEGER NOT NULL,"
                + " parity_ok INTEGER NOT NULL, UNIQUE(import_id, seq))");
        s.execute("CREATE TABLE events("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, import_id TEXT NOT NULL,"
                + " branch_id TEXT, seq INTEGER NOT NULL, ts TEXT NOT NULL,"
                + " actor TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL,"
                + " UNIQUE(import_id, seq))");
    }

    /** v2：分支与人工裁定（含乐观并发版本）。 */
    private static void migration2(Statement s) throws SQLException {
        s.execute("CREATE TABLE branches("
                + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, name TEXT NOT NULL,"
                + " parent_id TEXT, created_event INTEGER NOT NULL, status TEXT NOT NULL)");
        s.execute("CREATE TABLE decisions("
                + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, branch_id TEXT NOT NULL,"
                + " rev INTEGER NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL,"
                + " outcome TEXT NOT NULL, note TEXT, actor TEXT NOT NULL,"
                + " created_event INTEGER NOT NULL, reverted_event INTEGER)");
    }

    /** 派生结论：来源链与失效范围。 */
    private static void migration3(Statement s) throws SQLException {
        s.execute("CREATE TABLE conclusions("
                + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, branch_id TEXT NOT NULL,"
                + " kind TEXT NOT NULL, target TEXT NOT NULL, value TEXT NOT NULL,"
                + " deps TEXT NOT NULL, status TEXT NOT NULL,"
                + " created_event INTEGER NOT NULL, superseded_event INTEGER)");
    }
}
