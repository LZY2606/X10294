package a429.store;

import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQLite connection factory with explicit, ordered schema migrations.
 * Migrations are recorded in schema_meta and never re-run.
 */
public final class Database implements AutoCloseable {

    public static final int LATEST_VERSION = 2;

    private final SQLiteDataSource dataSource;
    private final String url;

    public Database(String path) {
        this.url = "jdbc:sqlite:" + path;
        this.dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        dataSource.setEnforceForeignKeys(false);
        migrate();
    }

    public Connection connection() throws SQLException {
        Connection c = dataSource.getConnection();
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        return c;
    }

    public int currentVersion() {
        try (Connection c = connection()) {
            boolean metaExists;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_meta'")) {
                metaExists = rs.next();
            }
            if (!metaExists) {
                return 0;
            }
            try (Statement st = c.createStatement();
                 ResultSet v = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_meta")) {
                return v.next() ? v.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read schema version", e);
        }
    }

    public void migrate() {
        try (Connection c = connection()) {
            boolean metaExists;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_meta'")) {
                metaExists = rs.next();
            }
            int version = 0;
            if (metaExists) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_meta")) {
                    version = rs.next() ? rs.getInt(1) : 0;
                }
            }
            for (int target = version + 1; target <= LATEST_VERSION; target++) {
                String ddl = readMigration(target);
                try (Statement st = c.createStatement()) {
                    for (String stmt : splitStatements(ddl)) {
                        st.execute(stmt);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_meta(version, applied_at) VALUES (?,?)")) {
                    ps.setInt(1, target);
                    ps.setString(2, Instant.now().toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    static List<String> splitStatements(String ddl) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : ddl.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--")) {
                continue;
            }
            cur.append(line).append('\n');
        }
        for (String part : cur.toString().split(";")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String readMigration(int version) throws IOException {
        String name = "/db/migration/V" + version + "__" + migrationName(version) + ".sql";
        try (var in = Database.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing migration " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String migrationName(int version) {
        return switch (version) {
            case 1 -> "baseline";
            case 2 -> "event_kind_tags";
            default -> throw new IllegalArgumentException("unknown migration " + version);
        };
    }

    @Override
    public void close() {
    }
}
