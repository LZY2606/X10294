package a429.store;

import a429.events.Event;
import a429.events.EventType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Thin persistence layer over the append-only tables. */
public final class Repository {
    private final Database database;

    public Repository(Database database) {
        this.database = database;
    }

    public Database database() {
        return database;
    }

    public void upsertChannelConfig(String channel, String bus, String expectedLabelsJson) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO channel_config(channel, bus, expected_labels_json) VALUES(?,?,?) "
                        + "ON CONFLICT(channel) DO UPDATE SET bus=excluded.bus, "
                        + "expected_labels_json=excluded.expected_labels_json")) {
            ps.setString(1, channel);
            ps.setString(2, bus);
            ps.setString(3, expectedLabelsJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putDictionary(String dictId, String version, String defsJson, Long eventId) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO dictionary_rev(dict_id, version, defs_json, created_event_id) "
                        + "VALUES(?,?,?,?) ON CONFLICT(dict_id, version) DO UPDATE SET "
                        + "defs_json=excluded.defs_json, created_event_id=excluded.created_event_id")) {
            ps.setString(1, dictId);
            ps.setString(2, version);
            ps.setString(3, defsJson);
            if (eventId == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, eventId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putParams(String paramsId, String version, double tolerance, double gapFactor,
                          int maxCandidates, Long eventId) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO params_rev(params_id, version, tolerance_ratio, gap_factor, "
                        + "max_candidates, created_event_id) VALUES(?,?,?,?,?,?) "
                        + "ON CONFLICT(params_id, version) DO UPDATE SET "
                        + "tolerance_ratio=excluded.tolerance_ratio, "
                        + "gap_factor=excluded.gap_factor, max_candidates=excluded.max_candidates, "
                        + "created_event_id=excluded.created_event_id")) {
            ps.setString(1, paramsId);
            ps.setString(2, version);
            ps.setDouble(3, tolerance);
            ps.setDouble(4, gapFactor);
            ps.setInt(5, maxCandidates);
            if (eventId == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, eventId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record BranchRow(String branchId, String parentBranchId, Integer forkedFromSeq,
                            String dictId, String dictVersion, String paramsId,
                            String paramsVersion, long createdAtNanos, String label) {
    }

    public void putBranch(BranchRow row) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO branch(branch_id, parent_branch_id, forked_from_seq, dict_id, "
                        + "dict_version, params_id, params_version, created_at_nanos, label) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, row.branchId());
            if (row.parentBranchId() == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, row.parentBranchId());
            }
            if (row.forkedFromSeq() == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, row.forkedFromSeq());
            }
            ps.setString(4, row.dictId());
            ps.setString(5, row.dictVersion());
            ps.setString(6, row.paramsId());
            ps.setString(7, row.paramsVersion());
            ps.setLong(8, row.createdAtNanos());
            ps.setString(9, row.label());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public BranchRow getBranch(String branchId) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT branch_id, parent_branch_id, forked_from_seq, dict_id, dict_version, "
                        + "params_id, params_version, created_at_nanos, label FROM branch "
                        + "WHERE branch_id = ?")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return readBranch(rs);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<BranchRow> listBranches() {
        List<BranchRow> rows = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT branch_id, parent_branch_id, forked_from_seq, dict_id, dict_version, "
                        + "params_id, params_version, created_at_nanos, label FROM branch "
                        + "ORDER BY created_at_nanos, branch_id")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(readBranch(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private BranchRow readBranch(ResultSet rs) throws SQLException {
        int parentSeq = rs.getInt(3);
        return new BranchRow(rs.getString(1), rs.getString(2), rs.wasNull() ? null : parentSeq,
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getLong(8), rs.getString(9));
    }

    public record RawWordRow(String wordUid, String branchId, int seq, long tsNanos,
                             String channel, int word, boolean reversedCapture,
                             long firstEventId, Long supersededByEventId) {
    }

    public void insertRawWord(RawWordRow row) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO raw_word(word_uid, branch_id, seq, ts_nanos, channel, word, "
                        + "reversed_capture, first_event_id, superseded_by_event_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, row.wordUid());
            ps.setString(2, row.branchId());
            ps.setInt(3, row.seq());
            ps.setLong(4, row.tsNanos());
            ps.setString(5, row.channel());
            ps.setInt(6, row.word());
            ps.setInt(7, row.reversedCapture ? 1 : 0);
            ps.setLong(8, row.firstEventId());
            if (row.supersededByEventId() == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setLong(9, row.supersededByEventId());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void markSuperseded(String branchId, int seq, long eventId) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "UPDATE raw_word SET superseded_by_event_id = ? WHERE branch_id = ? AND seq = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, branchId);
            ps.setInt(3, seq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<RawWordRow> listRawWords(String branchId) {
        List<RawWordRow> rows = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT word_uid, branch_id, seq, ts_nanos, channel, word, reversed_capture, "
                        + "first_event_id, superseded_by_event_id FROM raw_word "
                        + "WHERE branch_id = ? ORDER BY seq")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long sup = rs.getLong(9);
                rows.add(new RawWordRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getInt(7) == 1,
                        rs.getLong(8), rs.wasNull() ? null : sup));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public long appendEvent(String branchId, int seq, EventType type, String payloadJson,
                            String rationale, long createdAtNanos, String dependencyEdgesJson) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO event(branch_id, seq, type, payload_json, rationale, "
                        + "created_at_nanos, dependency_edges_json) VALUES(?,?,?,?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, branchId);
            ps.setInt(2, seq);
            ps.setString(3, type.name());
            ps.setString(4, payloadJson);
            ps.setString(5, rationale == null ? "" : rationale);
            ps.setLong(6, createdAtNanos);
            ps.setString(7, dependencyEdgesJson == null ? "[]" : dependencyEdgesJson);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Event> listEvents(String branchId) {
        List<Event> events = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, branch_id, seq, type, payload_json, rationale, created_at_nanos, "
                        + "dependency_edges_json FROM event WHERE branch_id = ? ORDER BY seq")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                events.add(new Event(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        EventType.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6),
                        rs.getLong(7), rs.getString(8)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return events;
    }

    public int maxEventSeq(String branchId) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) FROM event WHERE branch_id = ?")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putAdjudication(String branchId, String scopeUid, long eventId, String decision) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO adjudication(branch_id, scope_uid, event_id, decision, revoked_event_id) "
                        + "VALUES(?,?,?,?,NULL) ON CONFLICT(branch_id, scope_uid) DO UPDATE SET "
                        + "event_id=excluded.event_id, decision=excluded.decision, "
                        + "revoked_event_id=NULL")) {
            ps.setString(1, branchId);
            ps.setString(2, scopeUid);
            ps.setLong(3, eventId);
            ps.setString(4, decision);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record AdjudicationRow(String scopeUid, long eventId, String decision,
                                  Long revokedEventId) {
    }

    public List<AdjudicationRow> listAdjudications(String branchId) {
        List<AdjudicationRow> rows = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT scope_uid, event_id, decision, revoked_event_id FROM adjudication "
                        + "WHERE branch_id = ?")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long revoked = rs.getLong(4);
                rows.add(new AdjudicationRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.wasNull() ? null : revoked));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public void revokeAdjudication(String branchId, String scopeUid, long revokeEventId) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "UPDATE adjudication SET revoked_event_id = ? WHERE branch_id = ? AND scope_uid = ?")) {
            ps.setLong(1, revokeEventId);
            ps.setString(2, branchId);
            ps.setString(3, scopeUid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getDictionaryDefs(String dictId, String version) {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT defs_json FROM dictionary_rev WHERE dict_id=? AND version=?")) {
            ps.setString(1, dictId);
            ps.setString(2, version);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
