package avdata.arinc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read/write operations over {@link Db}. Writes are transactional; a failed import rolls the
 *  whole transaction back, never leaving a half record. */
public final class Repository {

    private final Db db;

    public Repository(Db db) {
        this.db = db;
    }

    public interface Tx {
        void run() throws Exception;
    }

    /** Run {@code tx} atomically. Any exception rolls back and is rethrown as
     *  {@link ImportException}. */
    public void transaction(Tx tx) {
        try {
            tx.run();
            db.conn().commit();
        } catch (ImportException e) {
            db.rollback();
            throw e;
        } catch (Exception e) {
            db.rollback();
            throw new ImportException("import failed and was fully rolled back: "
                    + e.getMessage(), e);
        }
    }

    public static final class ImportException extends RuntimeException {
        public ImportException(String message) {
            super(message);
        }

        public ImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // materials & words
    // ------------------------------------------------------------------

    public boolean materialExists(String materialUid) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select 1 from materials where uid=?")) {
            ps.setString(1, materialUid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void insertMaterial(String uid, String caseId, String kind, long createdMs,
                               String contentJson) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert into materials(uid,case_id,kind,created_ms,content_json) "
                        + "values(?,?,?,?,?)")) {
            ps.setString(1, uid);
            ps.setString(2, caseId);
            ps.setString(3, kind);
            ps.setLong(4, createdMs);
            ps.setString(5, contentJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ImportException("material insert failed: " + e.getMessage());
        }
    }

    public void insertWord(Records.Word w) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert into raw_words(uid,material_id,seq,received_ms,received_text,channel,"
                        + "raw_hex,reversed_words,import_parity,dict_hint) values(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, w.uid);
            ps.setString(2, w.materialId);
            ps.setInt(3, w.seq);
            ps.setLong(4, w.receivedEpochMs);
            ps.setString(5, w.receivedText);
            ps.setString(6, w.channel);
            ps.setString(7, w.rawHex);
            ps.setInt(8, w.reversedWords ? 1 : 0);
            if (w.importParity == null) {
                ps.setNull(9, Types.INTEGER);
            } else {
                ps.setInt(9, w.importParity ? 1 : 0);
            }
            ps.setString(10, w.dictionaryVersionHint);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ImportException("word insert failed at seq " + w.seq + ": " + e.getMessage());
        }
    }

    public List<Records.Word> loadWords(String materialId) {
        List<Records.Word> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select uid,material_id,seq,received_ms,received_text,channel,raw_hex,"
                        + "reversed_words,import_parity,dict_hint from raw_words "
                        + "where material_id=? order by seq")) {
            ps.setString(1, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Boolean imp = rs.getObject(9) == null ? null : rs.getInt(9) == 1;
                    out.add(new Records.Word(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getInt(8) == 1, imp, rs.getString(10)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<String> listMaterials(String caseId) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select uid from materials where case_id=? order by created_ms")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public String materialContent(String materialUid) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("select content_json from materials where uid=?")) {
            ps.setString(1, materialUid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // channels & dictionaries
    // ------------------------------------------------------------------

    public void upsertChannel(String caseId, Records.ChannelConfig c) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert or replace into channels(case_id,channel,nominal_kbps,schedule_slot_ms,"
                        + "polarity) values(?,?,?,?,?)")) {
            ps.setString(1, caseId);
            ps.setString(2, c.channel);
            ps.setDouble(3, c.nominalKbps);
            ps.setDouble(4, c.scheduleSlotMs);
            ps.setString(5, c.polarity);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ImportException("channel upsert failed: " + e.getMessage());
        }
    }

    public Map<String, Records.ChannelConfig> loadChannels(String caseId) {
        java.util.LinkedHashMap<String, Records.ChannelConfig> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select channel,nominal_kbps,schedule_slot_ms,polarity from channels where case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new Records.ChannelConfig(rs.getString(1),
                            rs.getDouble(2), rs.getDouble(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public void insertDictionary(String caseId, Records.DictionaryVersion dv) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert into dict_versions(case_id,version,ssm_rules,note) values(?,?,?,?)")) {
            ps.setString(1, caseId);
            ps.setString(2, dv.version);
            ps.setString(3, dv.ssmRules.name());
            ps.setString(4, dv.note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ImportException("dictionary insert failed: " + e.getMessage());
        }
        int ord = 0;
        for (ArincCodec.LabelDef def : dv.labels) {
            insertLabelDef(caseId, dv.version, def, ord++);
        }
    }

    private void insertLabelDef(String caseId, String version, ArincCodec.LabelDef def, int ord) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "insert into label_defs(case_id,dict_version,label,sdi,name,unit,coord_base,"
                        + "encoding,msb,lsb,resolution,discrete_json,ord) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 0;
            ps.setString(++i, caseId);
            ps.setString(++i, version);
            ps.setString(++i, def.label);
            ps.setInt(++i, def.sdi);
            ps.setString(++i, def.name);
            ps.setString(++i, def.unit);
            ps.setString(++i, def.coordinateBase);
            ps.setString(++i, def.encoding.name());
            if (def.msb <= 0) {
                ps.setNull(++i, Types.INTEGER);
            } else {
                ps.setInt(++i, def.msb);
            }
            if (def.lsb <= 0) {
                ps.setNull(++i, Types.INTEGER);
            } else {
                ps.setInt(++i, def.lsb);
            }
            ps.setDouble(++i, def.resolution);
            ps.setString(++i, Json.write(def.discreteNames));
            ps.setInt(++i, ord);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ImportException("label def insert failed: " + e.getMessage());
        }
    }

    public List<String> listDictionaryVersions(String caseId) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select version from dict_versions where case_id=? order by rowid")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Records.DictionaryVersion loadDictionary(String caseId, String version) {
        String rulesName;
        String note;
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select ssm_rules,note from dict_versions where case_id=? and version=?")) {
            ps.setString(1, caseId);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                rulesName = rs.getString(1);
                note = rs.getString(2);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        List<ArincCodec.LabelDef> defs = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "select label,sdi,name,unit,coord_base,encoding,msb,lsb,resolution,discrete_json "
                        + "from label_defs where case_id=? and dict_version=? order by ord")) {
            ps.setString(1, caseId);
            ps.setString(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sdi = rs.getInt(2);
                    List<String> names = Json.MAPPER.readValue(rs.getString(10), List.class);
                    defs.add(new ArincCodec.LabelDef(rs.getString(1), sdi, rs.getString(3),
                            rs.getString(4), rs.getString(5),
                            ArincCodec.Encoding.valueOf(rs.getString(6)),
                            rs.getObject(7) == null ? 0 : rs.getInt(7),
                            rs.getObject(8) == null ? 0 : rs.getInt(8),
                            rs.getDouble(9), names));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Records.DictionaryVersion(version,
                ArincCodec.SsmRuleSet.valueOf(rulesName), note, defs);
    }
}
