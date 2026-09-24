package arinc429.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Low-level persistence helpers. All writes use explicit transactions. */
public final class Store {

    private final Database db;

    public Store(Database db) {
        this.db = db;
    }

    public Connection connection() {
        return db.connect();
    }

    public static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            Object v = rs.getObject(i);
            m.put(rs.getMetaData().getColumnLabel(i), v);
        }
        return m;
    }

    public List<Map<String, Object>> query(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(row(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    public Map<String, Object> queryOne(Connection c, String sql, Object... args) {
        List<Map<String, Object>> rows = query(c, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update failed: " + sql, e);
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
