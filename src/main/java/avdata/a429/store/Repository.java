package avdata.a429.store;

import avdata.a429.service.Models;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Thin SQL access for sources, events, branches and artifacts. */
public final class Repository implements AutoCloseable {
    private final Connection conn;

    public Repository(Connection conn) {
        this.conn = conn;
    }

    public Connection connection() { return conn; }

    // ---------- meta ----------
    public void putMeta(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public String getMeta(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- reference versions ----------
    public void insertChannelConfig(String versionId, String convention, String note, long now) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO channel_config(version_id,label_convention,note,created_at_us) "
                        + "VALUES(?,?,?,?)")) {
            ps.setString(1, versionId);
            ps.setString(2, convention);
            ps.setString(3, note);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public void insertDictionary(String versionId, String payloadJson, long now) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO dictionary(version_id,payload_json,created_at_us) VALUES(?,?,?)")) {
            ps.setString(1, versionId);
            ps.setString(2, payloadJson);
            ps.setLong(3, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public String dictionaryPayload(String versionId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT payload_json FROM dictionary WHERE version_id=?")) {
            ps.setString(1, versionId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> dictionaryVersions() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT version_id FROM dictionary ORDER BY created_at_us");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------- import batch + raw words (one transaction per batch) ----------
    public void insertBatch(String batchId, long now, String sourceName, String channel,
                            String dictVersion, String configVersion, String note) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO import_batch(batch_id,imported_at_us,source_name,channel,"
                        + "dict_version,config_version,note) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, batchId);
            ps.setLong(2, now);
            ps.setString(3, sourceName);
            ps.setString(4, channel);
            ps.setString(5, dictVersion);
            ps.setString(6, configVersion);
            ps.setString(7, note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public void insertWord(String wordId, String batchId, long seq, long receivedAtUs,
                           String channel, int rawBits, String rawRepr) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO raw_word(word_id,batch_id,seq,received_at_us,channel,raw_bits,raw_repr) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, wordId);
            ps.setString(2, batchId);
            ps.setLong(3, seq);
            ps.setLong(4, receivedAtUs);
            ps.setString(5, channel);
            ps.setInt(6, rawBits);
            ps.setString(7, rawRepr);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public List<Models.WordView> wordsOfBatch(String batchId) {
        List<Models.WordView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT word_id,batch_id,seq,received_at_us,channel,raw_bits,raw_repr "
                        + "FROM raw_word WHERE batch_id=? ORDER BY seq")) {
            ps.setString(1, batchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int bits = rs.getInt(6);
                out.add(new Models.WordView(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), bits, rs.getString(7),
                        avdata.a429.decode.Bits.hex8(bits), avdata.a429.decode.Bits.bin32(bits)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<String> allBatchIds() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT batch_id FROM import_batch ORDER BY imported_at_us");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public String batchInfo(String batchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json_object('batchId',batch_id,'importedAtUs',imported_at_us,"
                        + "'sourceName',source_name,'channel',channel,'dictVersion',dict_version,"
                        + "'configVersion',config_version,'note',note) FROM import_batch WHERE batch_id=?")) {
            ps.setString(1, batchId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- branches ----------
    public void insertBranch(String branchId, String parentId, String name, long now) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branch(branch_id,parent_branch_id,name,created_at_us,tip_event_id) "
                        + "VALUES(?,?,?,?,NULL)")) {
            ps.setString(1, branchId);
            ps.setString(2, parentId);
            ps.setString(3, name);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public Models.BranchView branch(String branchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT branch_id,parent_branch_id,name,created_at_us,tip_event_id "
                        + "FROM branch WHERE branch_id=?")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return new Models.BranchView(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getLong(4), rs.getString(5));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Models.BranchView> branches() {
        List<Models.BranchView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT branch_id,parent_branch_id,name,created_at_us,tip_event_id FROM branch ORDER BY created_at_us");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Models.BranchView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void setBranchTip(String branchId, String eventId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE branch SET tip_event_id=? WHERE branch_id=?")) {
            ps.setString(1, eventId);
            ps.setString(2, branchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public long nextEventSeq(String branchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(seq)+1,0) FROM event WHERE branch_id=?")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- events ----------
    public void insertEvent(String eventId, String branchId, long seq, String parentEventId,
                            String kind, long now, String actor, String payloadJson,
                            String rationale, List<Models.DepView> deps) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO event(event_id,branch_id,seq,parent_event_id,kind,occurred_at_us,"
                        + "actor,payload_json,rationale) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, eventId);
            ps.setString(2, branchId);
            ps.setLong(3, seq);
            ps.setString(4, parentEventId);
            ps.setString(5, kind);
            ps.setLong(6, now);
            ps.setString(7, actor);
            ps.setString(8, payloadJson);
            ps.setString(9, rationale);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO event_dep(event_id,dep_type,dep_ref) VALUES(?,?,?)")) {
            for (Models.DepView d : deps) {
                ps.setString(1, eventId);
                ps.setString(2, d.depType());
                ps.setString(3, d.depRef());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public List<Models.EventView> eventsOfBranch(String branchId) {
        List<Models.EventView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id,branch_id,seq,parent_event_id,kind,occurred_at_us,actor,"
                        + "payload_json,rationale FROM event WHERE branch_id=? ORDER BY seq")) {
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.EventView(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), depsOf(rs.getString(1))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Models.DepView> depsOf(String eventId) {
        List<Models.DepView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT dep_type,dep_ref FROM event_dep WHERE event_id=? ORDER BY dep_type,dep_ref")) {
            ps.setString(1, eventId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.DepView(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Models.EventView event(String eventId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id,branch_id,seq,parent_event_id,kind,occurred_at_us,actor,"
                        + "payload_json,rationale FROM event WHERE event_id=?")) {
            ps.setString(1, eventId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return new Models.EventView(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                    rs.getString(8), rs.getString(9), depsOf(eventId));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Events that depend on the given reference; basis for invalidation scope. */
    public List<String> eventsDependingOn(String depType, String depRef) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id FROM event_dep WHERE dep_type=? AND dep_ref=? ORDER BY event_id")) {
            ps.setString(1, depType);
            ps.setString(2, depRef);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------- derived artifacts ----------
    public void insertArtifact(String artifactId, String identity, String branchId,
                               String producedByEvent, String kind, String status,
                               String targetRef, String payloadJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifact(artifact_id,identity,branch_id,produced_by_event,"
                        + "invalidated_by_event,kind,status,target_ref,payload_json) "
                        + "VALUES(?,?,?,?,NULL,?,?,?,?)")) {
            ps.setString(1, artifactId);
            ps.setString(2, identity);
            ps.setString(3, branchId);
            ps.setString(4, producedByEvent);
            ps.setString(5, kind);
            ps.setString(6, status);
            ps.setString(7, targetRef);
            ps.setString(8, payloadJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public void invalidateArtifactsOfEvent(String branchId, String byEvent,
                                           List<String> identities) {
        if (identities.isEmpty()) {
            return;
        }
        String in = String.join(",", identities.stream().map(x -> "?").toList());
        String sql = "UPDATE artifact SET invalidated_by_event=?, status='SUPERSEDED' "
                + "WHERE branch_id=? AND identity IN (" + in + ") AND status='ACTIVE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, byEvent);
            ps.setString(2, branchId);
            for (int i = 0; i < identities.size(); i++) {
                ps.setString(3 + i, identities.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public void rejectArtifacts(String branchId, String byEvent, List<String> identities) {
        if (identities.isEmpty()) {
            return;
        }
        String in = String.join(",", identities.stream().map(x -> "?").toList());
        String sql = "UPDATE artifact SET invalidated_by_event=?, status='REJECTED' "
                + "WHERE branch_id=? AND identity IN (" + in + ") AND status IN ('ACTIVE','SUPERSEDED')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, byEvent);
            ps.setString(2, branchId);
            for (int i = 0; i < identities.size(); i++) {
                ps.setString(3 + i, identities.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public List<Models.ArtifactView> artifactsAt(String branchId, String eventId) {
        List<Models.ArtifactView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT artifact_id,identity,branch_id,produced_by_event,"
                        + "COALESCE(invalidated_by_event,''),kind,status,target_ref,payload_json "
                        + "FROM artifact WHERE branch_id=? AND produced_by_event IS NOT NULL "
                        + "AND (SELECT seq FROM event WHERE event_id=produced_by_event) <= "
                        + "(SELECT seq FROM event WHERE event_id=?) "
                        + "AND COALESCE((SELECT seq FROM event e2 WHERE e2.event_id=artifact.invalidated_by_event),999999999) "
                        + "> (SELECT seq FROM event WHERE event_id=?) "
                        + "ORDER BY kind,target_ref")) {
            ps.setString(1, branchId);
            ps.setString(2, eventId);
            ps.setString(3, eventId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.ArtifactView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Models.ArtifactView> artifactsProducedBy(String eventId) {
        List<Models.ArtifactView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT artifact_id,identity,branch_id,produced_by_event,"
                        + "COALESCE(invalidated_by_event,''),kind,status,target_ref,payload_json "
                        + "FROM artifact WHERE produced_by_event=? ORDER BY kind,target_ref")) {
            ps.setString(1, eventId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new Models.ArtifactView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------- decisions ----------
    public void insertDecision(String decisionId, String eventId, String branchId, String action,
                               String targetIdentity, String baseEventId, String paramsJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO decision(decision_id,event_id,branch_id,action,target_identity,"
                        + "base_event_id,params_json,undone_by_event_id) VALUES(?,?,?,?,?,?,?,NULL)")) {
            ps.setString(1, decisionId);
            ps.setString(2, eventId);
            ps.setString(3, branchId);
            ps.setString(4, action);
            ps.setString(5, targetIdentity);
            ps.setString(6, baseEventId);
            ps.setString(7, paramsJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public void markDecisionUndone(String targetEventId, String undoEventId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE decision SET undone_by_event_id=? WHERE event_id=?")) {
            ps.setString(1, undoEventId);
            ps.setString(2, targetEventId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw rollback(e);
        }
    }

    public String decisionForEvent(String eventId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json_object('decisionId',decision_id,'eventId',event_id,'action',action,"
                        + "'targetIdentity',target_identity,'baseEventId',base_event_id,"
                        + "'params',params_json,'undoneBy',COALESCE(undone_by_event_id,'')) "
                        + "FROM decision WHERE event_id=?")) {
            ps.setString(1, eventId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- transaction control ----------
    public void commit() {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            }
    }

    private RuntimeException rollback(SQLException e) {
        rollback();
        return new IllegalStateException(e);
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
