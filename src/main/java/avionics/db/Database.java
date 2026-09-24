package avionics.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** SQLite 连接与状态迁移。迁移只前进，逐步应用并记录。 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
            }
        } catch (SQLException e) {
            throw new RuntimeException("打开数据库失败: " + path, e);
        }
    }

    public Connection conn() { return conn; }

    public int schemaVersion() {
        try (Statement s = conn.createStatement();
             var rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public void migrate() {
        try {
            conn.setAutoCommit(false);
            ensureMeta();
            int v = schemaVersion();
            List<String[]> migrations = Migrations.ALL;
            for (int i = v; i < migrations.size(); i++) {
                try (Statement s = conn.createStatement()) {
                    for (String sql : migrations.get(i)) s.execute(sql);
                    s.executeUpdate("UPDATE meta SET value='" + (i + 1) + "' WHERE key='schema_version'");
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
            throw new RuntimeException("状态迁移失败", e);
        }
    }

    private void ensureMeta() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("INSERT OR IGNORE INTO meta(key,value) VALUES('schema_version','0')");
        }
    }

    /** 在事务中执行；失败整体回滚，不留半份记录。 */
    public void inTx(Runnable r) {
        try {
            conn.setAutoCommit(false);
            r.run();
            conn.commit();
        } catch (RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
