package a429.store;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

/**
 * SQLite storage with ordered, tracked schema migrations. Migrations run inside a transaction;
 * any failure rolls the whole import/migration back, leaving no half records.
 */
public final class Database implements AutoCloseable {
    public static final List<String> MIGRATIONS = List.of(
            "db/migration/V1__baseline.sql",
            "db/migration/V2__archive.sql");

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path path) {
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.enforceForeignKeys(true);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties());
            Database database = new Database(connection);
            database.migrate();
            return database;
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    /** In-memory database used by tests. */
    public static Database memory() {
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.enforceForeignKeys(true);
            Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:",
                    config.toProperties());
            Database database = new Database(connection);
            database.migrate();
            return database;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Connection connection() {
        return connection;
    }

    public void migrate() {
        try {
            int current = currentVersion();
            for (int i = current; i < MIGRATIONS.size(); i++) {
                applyMigration(i + 1, MIGRATIONS.get(i));
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_meta ("
                    + "key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM schema_meta WHERE key = 'schema_version'")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private void applyMigration(int targetVersion, String resource) throws SQLException, IOException {
        String sql;
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing migration resource " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String part : splitStatements(sql)) {
                statement.execute(part);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    static List<String> splitStatements(String sql) {
        String stripped = stripSqlComments(sql);
        return Stream.of(stripped.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public void rollbackAndRethrow(SQLException e) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // keep original exception
        }
        throw new IllegalStateException(e);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
