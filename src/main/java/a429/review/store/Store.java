package a429.review.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite storage. All import work happens inside one transaction; any failure
 * rolls the whole import back so no half-imported record can remain.
 */
public final class Store implements AutoCloseable {

    private final Connection conn;

    public Store(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.conn.setAutoCommit(false);
            Schema.create(this.conn);
            this.conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    public void commit() {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    public void exec(String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
