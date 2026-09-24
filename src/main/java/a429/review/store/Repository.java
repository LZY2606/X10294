package a429.review.store;

import a429.review.domain.Events;
import a429.review.state.BranchRef;
import a429.review.state.Material;
import a429.review.state.RawWord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL facade over the append-only event log and source tables. */
public final class Repository {

    public record StoredEvent(long seq, String eventUid, String type, String branchId,
                              String scopeJson, String actor, String inputsHash,
                              String detailJson, String recordedAt) {
    }

    private final Store store;

    public Repository(Store store) {
        this.store = store;
    }

    public Store store() {
        return store;
    }

    public synchronized StoredEvent append(String type, String branchId, List<String> scope,
                                           String actor, String inputsHash, String detailJson) {
        long seq = nextSeq();
        String uid = a429.review.domain.Ids.eventId(seq, type, branchId, inputsHash);
        String scopeJson = a429.review.state.JsonCodecs.MAPPER.valueToTree(scope).toString();
        try (PreparedStatement ps = store.conn().prepareStatement("""
                insert into event_log(seq,event_uid,type,branch_id,branch_scope_json,actor,inputs_hash,detail_json,recorded_at)
                values(?,?,?,?,?,?,?,?,?)""")) {
            ps.setLong(1, seq);
            ps.setString(2, uid);
            ps.setString(3, type);
            ps.setString(4, branchId);
            ps.setString(5, scopeJson);
            ps.setString(6, actor);
            ps.setString(7, inputsHash);
            ps.setString(8, detailJson);
            ps.setString(9, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new StoredEvent(seq, uid, type, branchId, scopeJson, actor, inputsHash,
                detailJson, Instant.now().toString());
    }

    private long nextSeq() {
        try (PreparedStatement ps = store.conn().prepareStatement(
                "select coalesce(max(seq),0)+1 from event_log");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<StoredEvent> allEvents() {
        List<StoredEvent> out = new ArrayList<>();
        try (PreparedStatement ps = store.conn().prepareStatement(
                "select seq,event_uid,type,branch_id,branch_scope_json,actor,inputs_hash,detail_json,recorded_at "
                        + "from event_log order by seq");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public void saveDictionary(String version, String json) {
        try (PreparedStatement ps = store.conn().prepareStatement(
                "insert or replace into dictionary_blob(version,json) values(?,?)")) {
            ps.setString(1, version);
            ps.setString(2, json);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, String> dictionaries() {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = store.conn().prepareStatement("select version,json from dictionary_blob");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public void saveMaterial(Material m, String sourceName) {
        try (PreparedStatement ps = store.conn().prepareStatement(
                "insert or replace into material(material_id,case_id,imported_at,source_name) values(?,?,?,?)")) {
            ps.setString(1, m.materialId());
            ps.setString(2, m.caseId());
            ps.setString(3, Instant.now().toString());
            ps.setString(4, sourceName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try (PreparedStatement del = store.conn().prepareStatement("delete from raw_word where material_id=?")) {
            del.setString(1, m.materialId());
            del.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try (PreparedStatement ps = store.conn().prepareStatement("""
                insert into raw_word(material_id,ordinal,raw_hex,ts_raw,ts_ns,channel)
                values(?,?,?,?,?,?)""")) {
            for (RawWord rw : m.rawWords()) {
                ps.setString(1, rw.materialId());
                ps.setInt(2, rw.ordinal());
                ps.setString(3, rw.rawHex());
                ps.setString(4, rw.timestampRaw());
                ps.setLong(5, rw.timeNs());
                ps.setString(6, rw.channel());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Material loadMaterial() {
        String materialId = null;
        String caseId = null;
        try (PreparedStatement ps = store.conn().prepareStatement(
                "select material_id,case_id from material order by imported_at limit 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            materialId = rs.getString(1);
            caseId = rs.getString(2);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        List<RawWord> words = new ArrayList<>();
        try (PreparedStatement ps = store.conn().prepareStatement(
                "select ordinal,raw_hex,ts_raw,ts_ns,channel from raw_word where material_id=? order by ordinal")) {
            ps.setString(1, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    words.add(new RawWord(materialId, rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new Material(materialId, caseId, words, List.of(), List.of());
    }

    public void saveBranch(BranchRef ref) {
        try (PreparedStatement ps = store.conn().prepareStatement("""
                insert or replace into branch(branch_id,material_id,dictionary_version,created_at_seq,reason)
                values(?,?,?,?,?)""")) {
            ps.setString(1, ref.branchId());
            ps.setString(2, ref.materialId());
            ps.setString(3, ref.dictionaryVersion());
            ps.setLong(4, ref.createdAtSeq());
            ps.setString(5, ref.reason());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<BranchRef> branches() {
        List<BranchRef> out = new ArrayList<>();
        try (PreparedStatement ps = store.conn().prepareStatement(
                "select branch_id,material_id,dictionary_version,created_at_seq,reason from branch order by created_at_seq");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new BranchRef(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public boolean hasMaterial() {
        return loadMaterial() != null;
    }

    public void saveReplayOutcome(String replayId, int stepNo, boolean adopted,
                                  String assertion, boolean passed, String detailJson) {
        try (PreparedStatement ps = store.conn().prepareStatement("""
                insert or replace into replay_outcome(replay_id,step_no,adopted,assertion,passed,detail_json)
                values(?,?,?,?,?,?)""")) {
            ps.setString(1, replayId);
            ps.setInt(2, stepNo);
            ps.setInt(3, adopted ? 1 : 0);
            ps.setString(4, assertion);
            ps.setInt(5, passed ? 1 : 0);
            ps.setString(6, detailJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isReplayEventType(String t) {
        return Events.REPLAY_STEP.equals(t) || Events.CONFLICT_REJECTED.equals(t);
    }
}
