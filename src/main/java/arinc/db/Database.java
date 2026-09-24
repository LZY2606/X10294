package arinc.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** SQLite persistence with explicit, ordered schema migrations. */
public final class Database implements AutoCloseable {
  public static final int LATEST_VERSION = 2;

  private final Connection conn;

  public Database(String path) {
    try {
      File f = new File(path);
      if (f.getParentFile() != null) {
        f.getParentFile().mkdirs();
      }
      conn = DriverManager.getConnection("jdbc:sqlite:" + path);
      try (Statement s = conn.createStatement()) {
        s.execute("PRAGMA foreign_keys = ON");
        s.execute("PRAGMA busy_timeout = 5000");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot open database " + path, e);
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

  public void migrate() {
    migrateTo(LATEST_VERSION);
  }

  public synchronized void migrateTo(int target) {
    int v = schemaVersion();
    if (v < 1 && target >= 1) {
      applyV1();
      record(1);
      v = 1;
    }
    if (v < 2 && target >= 2) {
      applyV2();
      record(2);
    }
  }

  private void record(int version) {
    try (Statement s = conn.createStatement()) {
      s.execute("INSERT INTO schema_migrations(version, applied_at) VALUES ("
          + version + ",'" + Instant.now() + "')");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void applyV1() {
    String[] ddl = {
      "CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)",
      "CREATE TABLE imports("
          + "id INTEGER PRIMARY KEY, name TEXT NOT NULL, dict_version TEXT NOT NULL,"
          + " created_at TEXT NOT NULL)",
      "CREATE TABLE dictionaries(version TEXT PRIMARY KEY, json TEXT NOT NULL)",
      "CREATE TABLE raw_words("
          + "id INTEGER PRIMARY KEY, import_id INTEGER NOT NULL REFERENCES imports(id),"
          + " seq INTEGER NOT NULL, rx_time_ns INTEGER NOT NULL, channel TEXT NOT NULL,"
          + " word32 INTEGER NOT NULL, parity_ok INTEGER NOT NULL, label_octal TEXT NOT NULL,"
          + " sdi INTEGER NOT NULL, ssm INTEGER NOT NULL, data_field INTEGER NOT NULL,"
          + " dict_version TEXT NOT NULL, note TEXT)",
      "CREATE TABLE derived_values("
          + "id TEXT PRIMARY KEY, raw_word_id INTEGER NOT NULL REFERENCES raw_words(id),"
          + " dict_version TEXT NOT NULL, encoding TEXT NOT NULL, eng_value REAL, unit TEXT,"
          + " display TEXT NOT NULL, diagnostics TEXT NOT NULL, dict_hit TEXT NOT NULL)",
      "CREATE TABLE candidates("
          + "id INTEGER PRIMARY KEY, import_id INTEGER NOT NULL REFERENCES imports(id),"
          + " kind TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)",
      "CREATE TABLE branches("
          + "id INTEGER PRIMARY KEY, import_id INTEGER NOT NULL REFERENCES imports(id),"
          + " name TEXT NOT NULL, head_event_id INTEGER NOT NULL DEFAULT 0,"
          + " version INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)",
      "CREATE TABLE events("
          + "id INTEGER PRIMARY KEY, branch_id INTEGER NOT NULL REFERENCES branches(id),"
          + " parent_id INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL, kind TEXT NOT NULL,"
          + " payload TEXT NOT NULL, visible_state TEXT NOT NULL, created_at TEXT NOT NULL)"
    };
    runAll(ddl);
  }

  private void applyV2() {
    String[] ddl = {
      "ALTER TABLE events ADD COLUMN actor TEXT NOT NULL DEFAULT 'system'",
      "CREATE INDEX idx_raw_words_import ON raw_words(import_id, seq)",
      "CREATE INDEX idx_events_branch ON events(branch_id, seq)"
    };
    runAll(ddl);
  }

  private void runAll(String[] ddl) {
    try (Statement s = conn.createStatement()) {
      for (String sql : ddl) {
        s.execute(sql);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("migration failed: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
