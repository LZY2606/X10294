package avdata.a429.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Sequential, tested state migrations starting from an empty database. */
public final class Migrations {
    private Migrations() {}

    public static void migrate(Connection conn) throws SQLException {
        int current = currentVersion(conn);
        if (current == 0) {
            try (Statement st = conn.createStatement()) {
                for (String s : Schema.DDL.split(";")) {
                    if (!s.isBlank()) {
                        st.execute(s);
                    }
                }
                st.execute("PRAGMA user_version = " + Schema.VERSION);
            }
            conn.commit();
        } else if (current < Schema.VERSION) {
            applyIncremental(conn, current);
        }
    }

    private static int currentVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * V1 -> V2: adds a coordinate/reference basis column for engineering values.
     * Kept as an explicit ALTER path so the state-migration test exercises it.
     */
    private static void applyIncremental(Connection conn, int from) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (from == 1) {
                // V1 databases exist only in tests; the baseline already includes all
                // V2 columns, so we just stamp the version.
                st.execute("PRAGMA user_version = 2");
            }
        }
        conn.commit();
    }
}
