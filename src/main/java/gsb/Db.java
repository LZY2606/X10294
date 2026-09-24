package gsb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite access with stepwise migrations. Raw layer is append-only; nothing here mutates raw words. */
public final class Db implements AutoCloseable {

    public static final int SCHEMA_VERSION = 3;

    private final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA journal_mode = WAL");
            }
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    public int schemaVersion() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Stepwise migrations; each step is transactional and recorded. */
    public void migrate() {
        int v = schemaVersion();
        if (v == 0) {
            tx(() -> {
                exec("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
                exec("CREATE TABLE dictionaries("
                        + "dict_version TEXT NOT NULL, label_oct TEXT NOT NULL, name TEXT NOT NULL,"
                        + "encoding TEXT NOT NULL, scale REAL NOT NULL, unit TEXT NOT NULL,"
                        + "ssm_convention TEXT NOT NULL, valid_ssm TEXT NOT NULL,"
                        + "PRIMARY KEY(dict_version, label_oct))");
                exec("CREATE TABLE imports("
                        + "id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL,"
                        + "dict_version TEXT NOT NULL, status TEXT NOT NULL)");
                exec("CREATE TABLE words("
                        + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, seq INTEGER NOT NULL,"
                        + "recv_time_ms REAL NOT NULL, channel TEXT NOT NULL, raw32 INTEGER NOT NULL,"
                        + "parity_ok INTEGER NOT NULL, label_oct TEXT NOT NULL, sdi INTEGER NOT NULL,"
                        + "ssm INTEGER NOT NULL, data_field INTEGER NOT NULL, dict_version TEXT NOT NULL,"
                        + "FOREIGN KEY(import_id) REFERENCES imports(id))");
                exec("INSERT INTO schema_migrations(version, applied_at) VALUES(1, datetime('now'))");
            });
            v = 1;
        }
        if (v == 1) {
            tx(() -> {
                exec("CREATE TABLE branches("
                        + "id TEXT PRIMARY KEY, import_id TEXT NOT NULL, name TEXT NOT NULL,"
                        + "parent_event_id TEXT, created_at TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0,"
                        + "FOREIGN KEY(import_id) REFERENCES imports(id))");
                exec("CREATE TABLE events("
                        + "id TEXT PRIMARY KEY, branch_id TEXT, seq INTEGER NOT NULL, type TEXT NOT NULL,"
                        + "payload TEXT NOT NULL, created_at TEXT NOT NULL)");
                exec("INSERT INTO schema_migrations(version, applied_at) VALUES(2, datetime('now'))");
            });
            v = 2;
        }
        if (v == 2) {
            tx(() -> {
                exec("ALTER TABLE events ADD COLUMN actor TEXT NOT NULL DEFAULT 'system'");
                exec("CREATE TABLE decisions("
                        + "id TEXT PRIMARY KEY, event_id TEXT NOT NULL, target_id TEXT NOT NULL,"
                        + "kind TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL,"
                        + "FOREIGN KEY(event_id) REFERENCES events(id))");
                exec("CREATE TABLE invalidations("
                        + "id TEXT PRIMARY KEY, event_id TEXT NOT NULL, object_id TEXT NOT NULL,"
                        + "reason TEXT NOT NULL, FOREIGN KEY(event_id) REFERENCES events(id))");
                exec("INSERT INTO schema_migrations(version, applied_at) VALUES(3, datetime('now'))");
            });
        }
    }

    public void tx(Runnable body) {
        try {
            conn.setAutoCommit(false);
            try {
                body.run();
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback(); // failed import/change rolls back completely, no half records
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void exec(String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(sql, e);
        }
    }

    public void update(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(sql, e);
        }
    }

    public List<java.util.Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<java.util.Map<String, Object>> rows = new ArrayList<>();
                var md = rs.getMetaData();
                while (rs.next()) {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException(sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long l) ps.setLong(i + 1, l);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else if (a instanceof Double d) ps.setDouble(i + 1, d);
            else ps.setString(i + 1, a == null ? null : a.toString());
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
