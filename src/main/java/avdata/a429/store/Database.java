package avdata.a429.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    private Database() {}

    public static Connection open(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            Migrations.migrate(conn);
            return conn;
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }
}
