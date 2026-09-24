package a429.review.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Versioned schema; state migration tests walk every migration in order. */
final class Schema {

    static final int CURRENT_VERSION = 2;

    private Schema() {
    }

    static void create(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("create table if not exists schema_meta (id integer primary key, version integer not null)");
        }
        int v = currentVersion(conn);
        if (v == 0) {
            migrate1(conn);
            v = 1;
        }
        if (v < 2) {
            migrate2(conn);
            v = 2;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("delete from schema_meta");
            st.execute("insert into schema_meta(id, version) values (1, " + CURRENT_VERSION + ")");
        }
    }

    static int currentVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("select version from schema_meta order by id desc limit 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static void migrate1(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    create table material (
                        material_id text primary key,
                        case_id text not null,
                        imported_at text not null,
                        source_name text not null
                    )""");
            st.execute("""
                    create table raw_word (
                        material_id text not null,
                        ordinal integer not null,
                        raw_hex text not null,
                        ts_raw text not null,
                        ts_ns integer not null,
                        channel text not null,
                        primary key (material_id, ordinal)
                    )""");
            st.execute("""
                    create table channel_config (
                        material_id text not null,
                        channel text not null,
                        nominal_rates_json text not null,
                        primary key (material_id, channel)
                    )""");
            st.execute("""
                    create table dictionary_blob (
                        version text primary key,
                        json text not null
                    )""");
            st.execute("""
                    create table branch (
                        branch_id text primary key,
                        material_id text not null,
                        dictionary_version text not null,
                        created_at_seq integer not null,
                        reason text not null
                    )""");
            st.execute("""
                    create table event_log (
                        seq integer primary key,
                        event_uid text not null unique,
                        type text not null,
                        branch_id text not null,
                        branch_scope_json text not null,
                        actor text not null,
                        inputs_hash text not null,
                        detail_json text not null,
                        recorded_at text not null
                    )""");
            st.execute("""
                    create table material_source (
                        material_id text not null,
                        kind text not null,
                        ref text not null,
                        seq integer not null default 0,
                        payload_json text not null
                    )""");
        }
    }

    static void migrate2(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // migration 2: keep replay assertions (accepted + rejected outcomes)
            st.execute("""
                    create table replay_outcome (
                        replay_id text not null,
                        step_no integer not null,
                        adopted integer not null,
                        assertion text not null,
                        passed integer not null,
                        detail_json text not null,
                        primary key (replay_id, step_no, adopted)
                    )""");
        }
    }
}
