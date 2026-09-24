package a429.store;

import a429.domain.RawWord;
import a429.events.ConflictException;
import a429.events.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Append-only event log plus raw-word source storage. Nothing derived is
 * mutated in place: a source patch inserts a new revision row and marks the
 * old row superseded (the old bytes stay readable).
 */
public final class EventStore {

    private final Database db;

    public EventStore(Database db) {
        this.db = db;
    }

    public void createCaseIfAbsent(String caseId, String title, String packPath) {
        try (Connection c = db.connection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO cases(case_id,title,created_at,pack_path) VALUES(?,?,?,?)")) {
                ps.setString(1, caseId);
                ps.setString(2, title);
                ps.setString(3, Instant.now().toString());
                ps.setString(4, packPath);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("create case failed", e);
        }
    }

    public void createBranch(String caseId, String branchId, String parentBranchId,
                             String name, int atSeq) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR IGNORE INTO branches(case_id,branch_id,parent_branch_id,name,created_event_seq)"
                             + " VALUES(?,?,?,?,?)")) {
            ps.setString(1, caseId);
            ps.setString(2, branchId);
            ps.setString(3, parentBranchId);
            ps.setString(4, name);
            ps.setInt(5, atSeq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("create branch failed", e);
        }
    }

    public int headRevision(String caseId, String branchId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(revision),0) FROM events WHERE case_id=? AND branch_id=?")) {
            ps.setString(1, caseId);
            ps.setString(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Append an event; enforces the optimistic-lock base revision. */
    public Event append(String caseId, String branchId, String parentEventId, String type,
                        JsonNode payload, JsonNode depends, JsonNode invalidates,
                        String kind, JsonNode tags, Integer expectedRevision) {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                int maxSeq;
                int maxRevision;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(MAX(seq),-1), COALESCE(MAX(revision),0)"
                                + " FROM events WHERE case_id=? AND branch_id=?")) {
                    ps.setString(1, caseId);
                    ps.setString(2, branchId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        maxSeq = rs.getInt(1);
                        maxRevision = rs.getInt(2);
                    }
                }
                if (expectedRevision != null && expectedRevision != maxRevision) {
                    throw new ConflictException(type, expectedRevision, maxRevision);
                }
                int seq = maxSeq + 1;
                int revision = maxRevision + 1;
                String eventId = caseId + ":" + branchId + ":e" + seq;
                Event event = new Event(eventId, caseId, branchId, seq, parentEventId,
                        type, payload, depends, invalidates, Instant.now().toString(),
                        revision, kind, tags);
                insert(c, event);
                c.commit();
                return event;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("append failed", e);
        }
    }

    /** Insert with an explicit identity (used when copying events at a fork). */
    public Event appendWithIdentity(String caseId, String branchId, String parentEventId,
                                    String type, JsonNode payload, JsonNode depends,
                                    JsonNode invalidates, String kind, JsonNode tags,
                                    String eventId, int seq, int revision) {
        Event event = new Event(eventId, caseId, branchId, seq, parentEventId, type,
                payload, depends, invalidates, java.time.Instant.now().toString(),
                revision, kind, tags);
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                insert(c, event);
                c.commit();
                return event;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("appendWithIdentity failed", e);
        }
    }

    private void insert(Connection c, Event e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO events(event_id,case_id,branch_id,seq,parent_event_id,type,"
                        + "payload_json,depends_json,invalidates_json,created_at,revision,kind,tags_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 0;
            ps.setString(++i, e.eventId());
            ps.setString(++i, e.caseId());
            ps.setString(++i, e.branchId());
            ps.setInt(++i, e.seq());
            ps.setString(++i, e.parentEventId());
            ps.setString(++i, e.type());
            ps.setString(++i, Json.MAPPER.writeValueAsString(e.payload()));
            ps.setString(++i, Json.MAPPER.writeValueAsString(e.depends()));
            ps.setString(++i, Json.MAPPER.writeValueAsString(e.invalidates()));
            ps.setString(++i, e.createdAt());
            ps.setInt(++i, e.revision());
            ps.setString(++i, e.kind());
            ps.setString(++i, Json.MAPPER.writeValueAsString(e.tags()));
            ps.executeUpdate();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SQLException(ex);
        }
    }

    public List<String> branchIds(String caseId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT branch_id FROM branches WHERE case_id=? ORDER BY branch_id")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Event> events(String caseId, String branchId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM events WHERE case_id=? AND branch_id=? ORDER BY seq")) {
            ps.setString(1, caseId);
            ps.setString(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Event> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Event> eventAt(String caseId, String branchId, int seq) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM events WHERE case_id=? AND branch_id=? AND seq<=? ORDER BY seq DESC LIMIT 1")) {
            ps.setString(1, caseId);
            ps.setString(2, branchId);
            ps.setInt(3, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<RawWord> rawWords(String caseId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT word_id,channel_id,position,received_at_ns,received_at_iso,wire_hex,fragment_id,rev"
                             + " FROM raw_words WHERE case_id=? ORDER BY channel_id,rev,position")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RawWord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new RawWord(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Insert a fragment as a single transaction; any failure rolls back fully. */
    public void insertFragment(String caseId, String fragmentId, List<RawWord> words) {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO raw_words(word_id,case_id,fragment_id,channel_id,position,"
                            + "received_at_ns,received_at_iso,wire_hex,rev) VALUES(?,?,?,?,?,?,?,?,0)")) {
                for (RawWord w : words) {
                    ps.setString(1, w.wordId());
                    ps.setString(2, caseId);
                    ps.setString(3, fragmentId);
                    ps.setString(4, w.channelId());
                    ps.setInt(5, w.position());
                    ps.setLong(6, w.receivedAtNs());
                    ps.setString(7, w.receivedAtIso());
                    ps.setString(8, w.wireHex());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fragment insert rolled back: " + fragmentId, e);
        }
    }

    /** Apply one local source change as a new revision; previous bytes stay readable. */
    public RawWord patchWord(String caseId, RawWord current, String newWireHex,
                             int newReceivedNs, String newIso, int newRev, String newFragmentId) {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE raw_words SET superseded_by=? WHERE case_id=? AND word_id=? AND rev=?")) {
                    ps.setString(1, current.wordId() + ":r" + newRev);
                    ps.setString(2, caseId);
                    ps.setString(3, current.wordId());
                    ps.setInt(4, newRev - 1);
                    ps.executeUpdate();
                }
                RawWord patched = new RawWord(current.wordId(), current.channelId(),
                        current.position(), newReceivedNs, newIso, newWireHex, newFragmentId);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO raw_words(word_id,case_id,fragment_id,channel_id,position,"
                                + "received_at_ns,received_at_iso,wire_hex,rev) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, patched.wordId());
                    ps.setString(2, caseId);
                    ps.setString(3, newFragmentId);
                    ps.setString(4, patched.channelId());
                    ps.setInt(5, patched.position());
                    ps.setLong(6, patched.receivedAtNs());
                    ps.setString(7, patched.receivedAtIso());
                    ps.setString(8, patched.wireHex());
                    ps.setInt(9, newRev);
                    ps.executeUpdate();
                }
                c.commit();
                return patched;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("source patch rolled back", e);
        }
    }

    public int wordRevision(String caseId, String wordId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(rev),0) FROM raw_words WHERE case_id=? AND word_id=?")) {
            ps.setString(1, caseId);
            ps.setString(2, wordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> caseIds() {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT case_id FROM cases ORDER BY case_id");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public ArrayNode caseMeta(String caseId) {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT case_id,title,created_at,pack_path FROM cases WHERE case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                var n = Json.MAPPER.createObjectNode();
                n.put("caseId", rs.getString(1));
                n.put("title", rs.getString(2));
                n.put("createdAt", rs.getString(3));
                n.put("packPath", rs.getString(4));
                return n;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Event read(ResultSet rs) throws SQLException {
        try {
            return new Event(
                    rs.getString("event_id"),
                    rs.getString("case_id"),
                    rs.getString("branch_id"),
                    rs.getInt("seq"),
                    rs.getString("parent_event_id"),
                    rs.getString("type"),
                    Json.MAPPER.readTree(rs.getString("payload_json")),
                    Json.MAPPER.readTree(rs.getString("depends_json")),
                    Json.MAPPER.readTree(rs.getString("invalidates_json")),
                    rs.getString("created_at"),
                    rs.getInt("revision"),
                    rs.getString("kind"),
                    (JsonNode) Json.MAPPER.readTree(rs.getString("tags_json")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException(e);
        }
    }
}
