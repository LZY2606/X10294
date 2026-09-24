package arinc429.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite connection lifecycle and forward-only, versioned state migration. */
public final class Database implements AutoCloseable {

    private final String url;

    public Database(String path) {
        this.url = "jdbc:sqlite:" + path;
    }

    public Connection connect() {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
            }
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database: " + url, e);
        }
    }

    /** Apply pending migrations inside one transaction each; never partially. */
    public int migrate() {
        List<String> applied = new ArrayList<>();
        try (Connection c = connect()) {
            boolean metaExists;
            try (ResultSet rs = c.getMetaData().getTables(null, null, "schema_meta", null)) {
                metaExists = rs.next();
            }
            int current = 0;
            if (metaExists) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT value FROM schema_meta WHERE key='schema_version'")) {
                    if (rs.next()) {
                        current = rs.getInt(1);
                    }
                }
            }
            int to = current;
            for (int v = current + 1; ; v++) {
                String script = readMigration(v);
                if (script == null) {
                    break;
                }
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    st.executeUpdate(script.trim());
                    to = v;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO schema_meta(key, value) VALUES('schema_version', ?) "
                                    + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                        ps.setInt(1, v);
                        ps.executeUpdate();
                    }
                    c.commit();
                    applied.add("V" + v);
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("migration failed after " + applied, e);
        }
        return applied.size();
    }

    private String readMigration(int version) {
        String resource = "/migrations/V" + version + "__";
        String file = findMigrationFile(version);
        if (file == null) {
            return null;
        }
        try (InputStream in = Database.class.getResourceAsStream("/migrations/" + file)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read migration " + file, e);
        }
    }

    private String findMigrationFile(int version) {
        String prefix = "V" + version + "__";
        try (InputStream in = Database.class.getResourceAsStream("/migrations/")) {
            if (in != null) {
                String listing = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : listing.split("\\s+")) {
                    if (line.startsWith(prefix) && line.endsWith(".sql")) {
                        return line;
                    }
                }
            }
        } catch (IOException ignored) {
            // directory listing is not available on every classpath; fall through
        }
        String[] known = {
                "V1__baseline.sql",
                "V2__provenance_columns.sql"
        };
        String name = "V" + version + "__";
        for (String k : known) {
            if (k.startsWith(name)) {
                return k;
            }
        }
        return null;
    }

    public int currentVersion() {
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT value FROM schema_meta WHERE key='schema_version'")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        // SQLite connections are opened per operation; nothing pooled.
    }
}
