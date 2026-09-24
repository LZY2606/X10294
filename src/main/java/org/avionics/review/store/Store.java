package org.avionics.review.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.avionics.review.domain.EventRecord;
import org.avionics.review.domain.Invalidation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Event-sourced repository over SQLite.
 *
 * <p>Append-only events, optimistic-concurrency on branch heads, explicit
 * invalidation records computed from dependency scopes. Import of an archive
 * runs in one transaction: any failure rolls back so a half-written archive
 * can never remain.</p>
 */
public class Store {

    public static final String RULE_VERSION = "rules-1.0.0";

    public static class ConflictException extends RuntimeException {
        public final String expectedHead;
        public final String actualHead;
        public final List<EventRecord> unseen;

        public ConflictException(String expectedHead, String actualHead, List<EventRecord> unseen) {
            super("branch head conflict: expected " + expectedHead + " actual " + actualHead);
            this.expectedHead = expectedHead;
            this.actualHead = actualHead;
            this.unseen = unseen;
        }
    }

    private final Database db;
    private final ObjectMapper mapper;

    public Store(Database db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
        seedMainBranch();
    }

    private void seedMainBranch() {
        try {
            boolean exists;
            try (PreparedStatement ps = db.prepare("SELECT 1 FROM branches WHERE name='main'");
                 ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
            if (!exists) {
                try (PreparedStatement ps = db.prepare(
                        "INSERT INTO branches(name, parent_branch, fork_after_event, created_at) "
                                + "VALUES ('main', NULL, NULL, ?)")) {
                    ps.setString(1, Instant.now().toString());
                    ps.executeUpdate();
                }
                db.commit();
            }
        } catch (SQLException e) {
            db.rollback();
            throw new IllegalStateException(e);
        }
    }

    public List<String> listBranches() {
        try (PreparedStatement ps = db.prepare(
                "SELECT name FROM branches ORDER BY CASE name WHEN 'main' THEN 0 ELSE 1 END, name");
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

    public void forkBranch(String name, String parent, String afterEventId) {
        try {
            long forkSeq = Long.MAX_VALUE;
            if (afterEventId != null) {
                forkSeq = seqOf(parent, afterEventId);
            }
            try (PreparedStatement ps = db.prepare(
                    "INSERT INTO branches(name, parent_branch, fork_after_event, created_at) "
                            + "VALUES (?, ?, ?, ?)")) {
                ps.setString(1, name);
                ps.setString(2, parent);
                ps.setString(3, afterEventId);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
            long seq = 0;
            for (EventRecord ev : eventsUpTo(parent, forkSeq)) {
                insertEvent(name, ev.eventId(), seq++, ev.type(), ev.payload(),
                        ev.ruleVersion(), ev.createdAt(), ev.supersededBy());
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw new IllegalStateException(e);
        }
    }

    private long seqOf(String branch, String eventId) {
        try (PreparedStatement ps = db.prepare(
                "SELECT seq FROM events WHERE branch=? AND event_id=?")) {
            ps.setString(1, branch);
            ps.setString(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("event " + eventId + " not in branch " + branch);
    }

    private List<EventRecord> eventsUpTo(String branch, long maxSeq) {
        List<EventRecord> all = events(branch);
        List<EventRecord> out = new ArrayList<>();
        for (EventRecord ev : all) {
            if (ev.seq() <= maxSeq) {
                out.add(ev);
            }
        }
        return out;
    }

    public List<EventRecord> events(String branch) {
        try (PreparedStatement ps = db.prepare(
                "SELECT event_id, seq, type, payload, rule_version, created_at, superseded_by "
                        + "FROM events WHERE branch=? ORDER BY seq")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                List<EventRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new EventRecord(
                            rs.getString(1), rs.getLong(2), branch, rs.getString(3),
                            mapper.readTree(rs.getString(4)), rs.getString(5),
                            rs.getString(6), rs.getString(7)));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String headEventId(String branch) {
        try (PreparedStatement ps = db.prepare(
                "SELECT event_id FROM events WHERE branch=? ORDER BY seq DESC LIMIT 1")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public EventRecord append(String branch, String type, JsonNode payload) {
        return append(branch, type, payload, RULE_VERSION, null, null);
    }

    public EventRecord append(String branch, String type, JsonNode payload,
                              String ruleVersion, String expectedHead, String forcedEventId) {
        try {
            List<EventRecord> existing = events(branch);
            String actualHead = existing.isEmpty() ? null : existing.get(existing.size() - 1).eventId();
            if (expectedHead != null && !expectedHead.equals(actualHead)) {
                List<EventRecord> unseen = new ArrayList<>();
                boolean seen = false;
                for (EventRecord ev : existing) {
                    if (ev.eventId().equals(expectedHead)) {
                        seen = true;
                        continue;
                    }
                    if (seen) {
                        unseen.add(ev);
                    }
                }
                if (!seen) {
                    unseen = existing;
                }
                throw new ConflictException(expectedHead, actualHead, unseen);
            }
            String eventId = forcedEventId != null ? forcedEventId : UUID.randomUUID().toString();
            long seq = existing.size();
            EventRecord record = insertEvent(branch, eventId, seq, type, payload,
                    ruleVersion, Instant.now().toString(), null);
            db.commit();
            return record;
        } catch (ConflictException e) {
            db.rollback();
            throw e;
        } catch (Exception e) {
            db.rollback();
            throw new IllegalStateException(e);
        }
    }

    private EventRecord insertEvent(String branch, String eventId, long seq, String type,
                                    JsonNode payload, String ruleVersion,
                                    String createdAt, String supersededBy) throws SQLException {
        try (PreparedStatement ps = db.prepare(
                "INSERT INTO events(event_id, seq, branch, type, payload, rule_version, "
                        + "created_at, superseded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, eventId);
            ps.setLong(2, seq);
            ps.setString(3, branch);
            ps.setString(4, type);
            ps.setString(5, mapper.writeValueAsString(payload));
            ps.setString(6, ruleVersion);
            ps.setString(7, createdAt);
            ps.setString(8, supersededBy);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SQLException(e);
        }
        return new EventRecord(eventId, seq, branch, type, payload, ruleVersion, createdAt, supersededBy);
    }

    public EventRecord event(String branch, String eventId) {
        for (EventRecord ev : events(branch)) {
            if (ev.eventId().equals(eventId)) {
                return ev;
            }
        }
        return null;
    }

    public void addInvalidations(String eventId, List<Invalidation> invalidations) {
        if (invalidations.isEmpty()) {
            return;
        }
        try {
            try (PreparedStatement ps = db.prepare(
                    "INSERT INTO invalidations(event_id, scope_type, sid, reason) VALUES (?, ?, ?, ?)")) {
                for (Invalidation inv : invalidations) {
                    ps.setString(1, eventId);
                    ps.setString(2, inv.scopeType());
                    ps.setString(3, inv.sid());
                    ps.setString(4, inv.reason());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw new IllegalStateException(e);
        }
    }

    public List<Invalidation> invalidations(String eventId) {
        try (PreparedStatement ps = db.prepare(
                "SELECT scope_type, sid, reason FROM invalidations WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Invalidation> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Invalidation(eventId, rs.getString(1), rs.getString(2), rs.getString(3)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public ObjectNode exportArchive() {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", "arinc429-review-archive/1");
        root.put("exportedAt", Instant.now().toString());
        ArrayNode branches = root.putArray("branches");
        for (String name : listBranches()) {
            ObjectNode bn = branches.addObject();
            bn.put("name", name);
            ArrayNode evs = bn.putArray("events");
            for (EventRecord ev : events(name)) {
                ObjectNode en = evs.addObject();
                en.put("eventId", ev.eventId());
                en.put("seq", ev.seq());
                en.put("type", ev.type());
                en.set("payload", ev.payload());
                en.put("ruleVersion", ev.ruleVersion());
                en.put("createdAt", ev.createdAt());
            }
        }
        ArrayNode invs = root.putArray("invalidations");
        for (String name : listBranches()) {
            for (EventRecord ev : events(name)) {
                for (Invalidation inv : invalidations(ev.eventId())) {
                    ObjectNode in = invs.addObject();
                    in.put("eventId", inv.eventId());
                    in.put("scopeType", inv.scopeType());
                    in.put("sid", inv.sid());
                    in.put("reason", inv.reason());
                }
            }
        }
        return root;
    }

    public void importArchive(JsonNode root) {
        try {
            db.wipe();
            db.commit();
            seedMainBranch();
            for (JsonNode bn : root.get("branches")) {
                String name = bn.get("name").asText();
                if (!name.equals("main")) {
                    try (PreparedStatement ps = db.prepare(
                            "INSERT INTO branches(name, parent_branch, fork_after_event, created_at) "
                                    + "VALUES (?, ?, NULL, ?)")) {
                        ps.setString(1, name);
                        ps.setString(2, "main");
                        ps.setString(3, Instant.now().toString());
                        ps.executeUpdate();
                    }
                }
                long expectedSeq = 0;
                for (JsonNode en : bn.get("events")) {
                    insertEvent(name, en.get("eventId").asText(), expectedSeq++,
                            en.get("type").asText(), en.get("payload"),
                            en.path("ruleVersion").asText(RULE_VERSION),
                            en.path("createdAt").asText(Instant.now().toString()), null);
                }
            }
            if (root.has("invalidations")) {
                try (PreparedStatement ps = db.prepare(
                        "INSERT INTO invalidations(event_id, scope_type, sid, reason) VALUES (?, ?, ?, ?)")) {
                    for (JsonNode in : root.get("invalidations")) {
                        ps.setString(1, in.get("eventId").asText());
                        ps.setString(2, in.get("scopeType").asText());
                        ps.setString(3, in.get("sid").asText());
                        ps.setString(4, in.get("reason").asText());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw new IllegalArgumentException("archive import rolled back: " + e.getMessage(), e);
        }
    }

    public static String databasePath(String[] args) {
        String path = System.getProperty("review.db");
        if (path == null) {
            path = System.getenv("REVIEW_DB");
        }
        if (path == null) {
            path = "data/review.sqlite";
        }
        return path;
    }

    public void saveExportTo(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportArchive()));
    }
}
