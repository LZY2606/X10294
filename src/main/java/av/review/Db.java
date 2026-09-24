package av.review;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SQLite access plus forward-only schema migrations. Migration steps are
 * append-only; the migration test builds a v1 database by hand and verifies
 * the upgrade path preserves every row.
 */
public final class Db {

    public static final int SCHEMA_VERSION = 2;

    private final String url;

    private Db(String url) {
        this.url = url;
    }

    public static Db file(String path) {
        return new Db("jdbc:sqlite:" + path);
    }

    public static Db memory() {
        // Keep one shared in-memory database per Db instance via a private name.
        return new Db("jdbc:sqlite:file:avreview" + System.nanoTime() + "?mode=memory&cache=shared");
    }

    public Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    interface Migration {
        void apply(Connection c) throws SQLException;
    }

    private static final List<Migration> MIGRATIONS = List.of(
            Db::migrateToV1,
            Db::migrateToV2
    );

    public void migrate() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            int version = c.createStatement().executeQuery("PRAGMA user_version").getInt(1);
            for (int i = version; i < MIGRATIONS.size(); i++) {
                c.setAutoCommit(false);
                try {
                    MIGRATIONS.get(i).apply(c);
                    s.execute("PRAGMA user_version=" + (i + 1));
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("schema migration failed", e);
        }
    }

    private static void migrateToV1(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS dictionaries(
                      version TEXT PRIMARY KEY,
                      body TEXT NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS imports(
                      id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      dict_version TEXT NOT NULL,
                      created_seq INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS words(
                      id TEXT PRIMARY KEY,
                      import_id TEXT NOT NULL REFERENCES imports(id),
                      seq INTEGER NOT NULL,
                      rx_time_ns INTEGER NOT NULL,
                      channel TEXT NOT NULL,
                      raw_hex TEXT NOT NULL,
                      label_bits INTEGER NOT NULL,
                      label_octal TEXT NOT NULL,
                      sdi INTEGER NOT NULL,
                      ssm INTEGER NOT NULL,
                      data_field INTEGER NOT NULL,
                      parity_ok INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS branches(
                      id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      parent_id TEXT,
                      base_seq INTEGER NOT NULL,
                      created_seq INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS events(
                      seq INTEGER PRIMARY KEY AUTOINCREMENT,
                      id TEXT NOT NULL,
                      branch_id TEXT NOT NULL,
                      type TEXT NOT NULL,
                      payload TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )""");
        }
    }

    /** v2: provenance annotations — per-word dictionary version pin and free-form import note. */
    private static void migrateToV2(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE words ADD COLUMN dict_version TEXT NOT NULL DEFAULT ''");
            s.execute("ALTER TABLE words ADD COLUMN note TEXT NOT NULL DEFAULT ''");
            s.execute("ALTER TABLE imports ADD COLUMN note TEXT NOT NULL DEFAULT ''");
        }
    }
}
