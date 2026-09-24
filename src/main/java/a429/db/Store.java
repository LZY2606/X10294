package a429.db;

import a429.event.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed append-only event store.
 *
 * Writes are transactional: an import either inserts every row or rolls back,
 * never leaving a half batch. Optimistic conflict detection uses each branch's
 * head sequence; a stale baseSeq is rejected with a 409-style conflict.
 */
public class Store implements AutoCloseable {

    public static final int CURRENT_SCHEMA = 1;

    private final Connection conn;

    public Store(String jdbcUrl) {
        try {
            this.conn = DriverManager.getConnection(jdbcUrl);
            this.conn.setAutoCommit(false);
            migrate();
            this.conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open store: " + jdbcUrl, e);
        }
    }

    public Connection connection() {
        return conn;
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )""");
        }
        int version = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM schema_meta WHERE key='schema_version'")) {
            if (rs.next()) {
                version = Integer.parseInt(rs.getString(1));
            }
        }
        if (version >= 1) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE cases (
                      case_id TEXT PRIMARY KEY,
                      title TEXT NOT NULL,
                      created_epoch_ms INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE branches (
                      branch_id TEXT PRIMARY KEY,
                      case_id TEXT NOT NULL,
                      parent_branch_id TEXT,
                      forked_from_event_id TEXT,
                      created_epoch_ms INTEGER NOT NULL,
                      note TEXT,
                      head_seq INTEGER NOT NULL DEFAULT 0,
                      FOREIGN KEY (case_id) REFERENCES cases(case_id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE events (
                      event_id TEXT PRIMARY KEY,
                      case_id TEXT NOT NULL,
                      branch_id TEXT NOT NULL,
                      seq INTEGER NOT NULL,
                      type TEXT NOT NULL,
                      created_epoch_ms INTEGER NOT NULL,
                      actor TEXT NOT NULL,
                      payload TEXT NOT NULL,
                      basis TEXT,
                      UNIQUE(branch_id, seq)
                    )""");
            st.executeUpdate("CREATE INDEX idx_events_branch ON events(branch_id, seq)");
            st.executeUpdate("CREATE INDEX idx_events_case ON events(case_id, created_epoch_ms)");
            st.executeUpdate("INSERT INTO schema_meta(key, value) VALUES ('schema_version', '1')");
        }
    }

    public int schemaVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM schema_meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void createCase(String caseId, String title, long nowMs) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cases(case_id, title, created_epoch_ms) VALUES (?,?,?)")) {
            ps.setString(1, caseId);
            ps.setString(2, title);
            ps.setLong(3, nowMs);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    public void ensureBranch(String branchId, String caseId, String parentBranchId,
                             String forkedFromEventId, long nowMs, String note) {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                INSERT INTO branches(branch_id, case_id, parent_branch_id, forked_from_event_id,
                                     created_epoch_ms, note, head_seq)
                VALUES (?,?,?,?,?,?,0)
                ON CONFLICT(branch_id) DO NOTHING
                """)) {
            ps.setString(1, branchId);
            ps.setString(2, caseId);
            ps.setString(3, parentBranchId);
            ps.setString(4, forkedFromEventId);
            ps.setLong(5, nowMs);
            ps.setString(6, note);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    public record BranchRow(String branchId, String caseId, String parentBranchId,
                            String forkedFromEventId, long headSeq, String note) {
    }

    public List<BranchRow> branches(String caseId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT branch_id, case_id, parent_branch_id, forked_from_event_id, head_seq, note "
                        + "FROM branches WHERE case_id=? ORDER BY created_epoch_ms")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BranchRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new BranchRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getString(6)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long headSeq(String branchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT head_seq FROM branches WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown branch: " + branchId);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raised when the client's baseSeq is behind the branch head (two-window save conflict). */
    public static class ConflictException extends RuntimeException {
        public final long expected;
        public final long actual;

        public ConflictException(long expected, long actual) {
            super("version conflict: baseSeq=" + expected + " headSeq=" + actual);
            this.expected = expected;
            this.actual = actual;
        }
    }

    /**
     * Append a batch atomically. {@code baseSeq} is the head the client saw;
     * -1 disables the check (used during archive import).
     */
    public synchronized List<Event> appendBatch(String caseId, String branchId, long baseSeq,
                                                 String actor, List<NewEvent> events) {
        try {
            long head = headSeq(branchId);
            if (baseSeq >= 0 && baseSeq != head) {
                List<Event> theirs = eventsOnBranch(branchId, baseSeq, Long.MAX_VALUE, 20);
                throw new ConflictException(baseSeq, head);
            }
            List<Event> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            long seq = head;
            for (NewEvent ne : events) {
                seq++;
                String eventId = ne.eventId() != null ? ne.eventId()
                        : branchId + "-evt-" + seq;
                Event ev = new Event(eventId, caseId, branchId, seq, ne.type(),
                        ne.createdEpochMs() > 0 ? ne.createdEpochMs() : now, actor,
                        ne.payload(), ne.basis());
                insert(ev);
                out.add(ev);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE branches SET head_seq=? WHERE branch_id=?")) {
                ps.setLong(1, seq);
                ps.setString(2, branchId);
                ps.executeUpdate();
            }
            conn.commit();
            return out;
        } catch (ConflictException e) {
            rollback();
            throw e;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("append batch failed; transaction rolled back", e);
        }
    }

    /**
     * Insert a materialized inherited event into a child branch during fork,
     * preserving the parent's identity and historical seq.
     */
    public synchronized void setHeadSeq(String branchId, long seq) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE branches SET head_seq=? WHERE branch_id=?")) {
            ps.setLong(1, seq);
            ps.setString(2, branchId);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    public synchronized void setHeadSeqNoCommit(String branchId, long seq) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE branches SET head_seq=? WHERE branch_id=?")) {
            ps.setLong(1, seq);
            ps.setString(2, branchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    public synchronized void insertInherited(Event ev) {
        // No commit here: archive import stays atomic and commits through its caller.
        try {
            insert(ev);
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    private void insert(Event ev) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                INSERT INTO events(event_id, case_id, branch_id, seq, type, created_epoch_ms,
                                   actor, payload, basis)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, ev.eventId());
            ps.setString(2, ev.caseId());
            ps.setString(3, ev.branchId());
            ps.setLong(4, ev.seq());
            ps.setString(5, ev.type());
            ps.setLong(6, ev.createdEpochMs());
            ps.setString(7, ev.actor());
            ps.setString(8, ev.payload() == null ? "{}" : Json.MAPPER.writeValueAsString(ev.payload()));
            ps.setString(9, ev.basis() == null ? null : Json.MAPPER.writeValueAsString(ev.basis()));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SQLException("cannot serialize event " + ev.eventId(), e);
        }
    }

    public List<Event> events(String caseId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM events WHERE case_id=? ORDER BY created_epoch_ms, branch_id, seq")) {
            ps.setString(1, caseId);
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Event> eventsOnBranch(String branchId) {
        return eventsOnBranch(branchId, 0, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    public List<Event> eventsOnBranch(String branchId, long fromSeqExclusive, long toSeqInclusive,
                                      int limit) {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                SELECT * FROM events WHERE branch_id=? AND seq>? AND seq<=?
                ORDER BY seq LIMIT ?
                """)) {
            ps.setString(1, branchId);
            ps.setLong(2, fromSeqExclusive);
            ps.setLong(3, toSeqInclusive);
            ps.setInt(4, limit);
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Event eventById(String eventId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM events WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> caseIds() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT case_id FROM cases ORDER BY created_epoch_ms")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String caseTitle(String caseId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT title FROM cases WHERE case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Delete the entire local database contents (tests and the "delete local DB" verification). */
    public void wipe() {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM events");
            st.executeUpdate("DELETE FROM branches");
            st.executeUpdate("DELETE FROM cases");
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException(e);
        }
    }

    private List<Event> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Event> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        }
    }

    private Event map(ResultSet rs) throws SQLException {
        try {
            JsonNode payload = Json.MAPPER.readTree(rs.getString("payload"));
            String basisText = rs.getString("basis");
            JsonNode basis = basisText == null ? Json.obj() : Json.MAPPER.readTree(basisText);
            return new Event(rs.getString("event_id"), rs.getString("case_id"),
                    rs.getString("branch_id"), rs.getLong("seq"), rs.getString("type"),
                    rs.getLong("created_epoch_ms"), rs.getString("actor"), payload, basis);
        } catch (Exception e) {
            throw new SQLException("cannot read event row", e);
        }
    }

    private void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
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

    public record NewEvent(String eventId, String type, long createdEpochMs,
                           JsonNode payload, JsonNode basis) {
        public NewEvent(String type, JsonNode payload, JsonNode basis) {
            this(null, type, 0, payload, basis);
        }
    }
}
