package avdata.arinc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence.
 *
 * <p>Two layers are physically separate:
 * <ul>
 *   <li>source layer ({@code materials}, {@code raw_words}, {@code channels},
 *       {@code dict_versions}, {@code label_defs}) - write-once per material, never rewritten;</li>
 *   <li>append-only event layer ({@code branches}, {@code events}) - rulings, parameter
 *       revisions, source patches and conflicts.</li>
 * </ul>
 *
 * <p>Derived values are reconstructed on demand by replaying events against the source layer,
 * so importing can never irreversibly "clean" a word and old conclusions can never be
 * overwritten by a newer computation.
 */
public final class Db implements AutoCloseable {

    public static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            migrate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database: " + path, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    // ------------------------------------------------------------------
    // schema / migration
    // ------------------------------------------------------------------

    public void migrate() {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("create table if not exists meta ("
                    + "key text primary key, value text not null)");
            int from = readSchemaVersion();
            if (from == 0) {
                createV1(st);
                from = 1;
            }
            if (from == 1) {
                migrateToV2(st);
            }
            st.executeUpdate("insert or replace into meta(key,value) values('schema_version','"
                    + SCHEMA_VERSION + "')");
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("migration failed", e);
        }
    }

    private int readSchemaVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select value from meta where key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void createV1(Statement st) throws SQLException {
        st.executeUpdate("create table materials ("
                + "uid text primary key, case_id text not null, kind text not null, "
                + "created_ms integer not null, content_json text not null)");
        st.executeUpdate("create table raw_words ("
                + "uid text primary key, material_id text not null, seq integer not null, "
                + "received_ms integer not null, received_text text not null, "
                + "channel text not null, raw_hex text not null, reversed_words integer not null, "
                + "import_parity integer, dict_hint text, "
                + "unique(material_id, seq))");
        st.executeUpdate("create table channels ("
                + "case_id text not null, channel text not null, nominal_kbps real not null, "
                + "schedule_slot_ms real not null, polarity text not null, "
                + "primary key(case_id, channel))");
        st.executeUpdate("create table dict_versions ("
                + "case_id text not null, version text not null, ssm_rules text not null, "
                + "note text not null, primary key(case_id, version))");
        st.executeUpdate("create table label_defs ("
                + "case_id text not null, dict_version text not null, label text not null, "
                + "sdi integer not null, name text not null, unit text not null, "
                + "coord_base text not null, encoding text not null, msb integer, lsb integer, "
                + "resolution real, discrete_json text not null, ord integer not null, "
                + "primary key(case_id, dict_version, label, sdi))");
        st.executeUpdate("create table branches ("
                + "uid text primary key, case_id text not null, name text not null, "
                + "parent_branch text, base_event text, created_ms integer not null, "
                + "head_event text)");
        st.executeUpdate("create table events ("
                + "uid text primary key, case_id text not null, branch_id text not null, "
                + "epoch_ms integer not null, type text not null, parent_event text, "
                + "summary text not null, payload_json text not null, prev_hash text not null, "
                + "self_hash text not null, dep_basis text not null, ord integer not null)");
        st.executeUpdate("create table if not exists rulings ("
                + "uid text primary key, case_id text not null, target_uid text not null, "
                + "accepted integer not null, note text, revoked integer not null default 0)");
    }

    /** V2 adds an explicit dependency basis table so invalidation scopes are queryable and a
     *  revision column for optimistic concurrency on branches. */
    private void migrateToV2(Statement st) throws SQLException {
        st.executeUpdate("create table if not exists dep_basis ("
                + "case_id text not null, scope text not null, key text not null, "
                + "version text not null, since_event text not null, "
                + "primary key(case_id, scope, key))");
        st.executeUpdate("create table if not exists object_versions ("
                + "case_id text not null, object_uid text not null, version integer not null, "
                + "primary key(case_id, object_uid))");
        // columns added on top of V1 tables:
        addColumnIfMissing(st, "branches", "rev", "integer not null default 1");
    }

    private void addColumnIfMissing(Statement st, String table, String col, String ddl)
            throws SQLException {
        boolean exists;
        try (ResultSet rs = st.executeQuery("pragma table_info(" + table + ")")) {
            exists = false;
            while (rs.next()) {
                if (col.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            st.executeUpdate("alter table " + table + " add column " + col + " " + ddl);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // best effort
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
