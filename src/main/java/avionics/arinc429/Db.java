package avionics.arinc429;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** SQLite 打开与状态迁移。迁移只追加、不改写历史数据。 */
public final class Db {

    static final List<String[]> MIGRATIONS = List.of(
            // v1: 基线结构 —— 来源与解释分开保存
            new String[]{
                "CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)",
                """
                CREATE TABLE imports(
                  id TEXT PRIMARY KEY, name TEXT NOT NULL, channel_config TEXT NOT NULL,
                  dict_version TEXT NOT NULL, created_at TEXT NOT NULL)
                """,
                """
                CREATE TABLE words(
                  id TEXT PRIMARY KEY, import_id TEXT NOT NULL, seq INTEGER NOT NULL,
                  rx_nanos INTEGER NOT NULL, channel TEXT NOT NULL, raw INTEGER NOT NULL,
                  parity_ok INTEGER NOT NULL, label_octal INTEGER NOT NULL, label_text TEXT NOT NULL,
                  sdi INTEGER NOT NULL, ssm INTEGER NOT NULL, data_field INTEGER NOT NULL,
                  dict_version TEXT NOT NULL, UNIQUE(import_id, seq))
                """,
                "CREATE TABLE dicts(version TEXT PRIMARY KEY, entries TEXT NOT NULL)",
                """
                CREATE TABLE branches(
                  id TEXT PRIMARY KEY, import_id TEXT NOT NULL, name TEXT NOT NULL,
                  parent_id TEXT, dict_version TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL)
                """,
                """
                CREATE TABLE segments(
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, channel TEXT NOT NULL,
                  start_seq INTEGER NOT NULL, end_seq INTEGER NOT NULL,
                  gap_before INTEGER NOT NULL, status TEXT NOT NULL)
                """,
                """
                CREATE TABLE rate_candidates(
                  id TEXT PRIMARY KEY, segment_id TEXT NOT NULL, branch_id TEXT NOT NULL,
                  rate_hz REAL NOT NULL, interval_ms REAL NOT NULL, votes INTEGER NOT NULL,
                  status TEXT NOT NULL)
                """,
                """
                CREATE TABLE eng_values(
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, word_id TEXT NOT NULL,
                  encoding TEXT NOT NULL, value_text TEXT NOT NULL, unit TEXT NOT NULL,
                  frame TEXT NOT NULL, diagnostic TEXT, dict_version TEXT NOT NULL,
                  dict_entry_id TEXT NOT NULL, valid INTEGER NOT NULL DEFAULT 1)
                """,
                """
                CREATE TABLE decisions(
                  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, target_type TEXT NOT NULL,
                  target_id TEXT NOT NULL, action TEXT NOT NULL, base_version INTEGER NOT NULL,
                  payload TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL)
                """,
                """
                CREATE TABLE events(
                  id TEXT PRIMARY KEY, seq INTEGER NOT NULL, ts TEXT NOT NULL, type TEXT NOT NULL,
                  branch_id TEXT, payload TEXT NOT NULL)
                """
            },
            // v2: 事件可标记被哪条后续事件取代；裁定可撤销
            new String[]{
                "ALTER TABLE events ADD COLUMN supersedes TEXT",
                "ALTER TABLE decisions ADD COLUMN undone INTEGER NOT NULL DEFAULT 0"
            }
    );

    public static Connection open(String path) throws SQLException {
        if (path.contains("/")) {
            java.io.File parent = new java.io.File(path).getParentFile();
            if (parent != null) parent.mkdirs();
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
        }
        migrate(c);
        return c;
    }

    public static void migrate(Connection c) throws SQLException {
        int current = 0;
        try (Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            // 表可能尚不存在
            if (rs.next()) current = rs.getInt(1);
        } catch (SQLException ignored) {
            current = 0;
        }
        for (int v = current; v < MIGRATIONS.size(); v++) {
            try (Statement s = c.createStatement()) {
                for (String sql : MIGRATIONS.get(v)) s.execute(sql);
                s.execute("INSERT INTO schema_migrations(version, applied_at) VALUES("
                        + (v + 1) + ", datetime('now'))");
            }
        }
    }

    public static int version(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
