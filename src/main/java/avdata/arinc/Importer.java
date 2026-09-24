package avdata.arinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Imports a {@link CasePack} into the two-layer store inside a single transaction.
 *
 *  <p>Validation happens before any write: a malformed word or invalid reference rolls the
 *  whole import back, so half-records are impossible. Source representation is preserved
 *  verbatim (raw hex, receive-time text, flags); no cleaning/normalisation occurs here. */
public final class Importer {

    private final Repository repo;
    private final EventLog events;

    public Importer(Repository repo, EventLog events) {
        this.repo = repo;
        this.events = events;
    }

    public static final class ImportReport {
        public String caseId;
        public String materialUid;
        public int wordCount;
        public List<String> wordUids = new ArrayList<>();
        public String baselineEventUid;
    }

    public ImportReport importPack(CasePack pack, long now) {
        validate(pack);
        String materialUid = StableId.of("material", pack.caseId, pack.material.id);
        if (repo.materialExists(materialUid)) {
            throw new Repository.ImportException("material already imported: " + materialUid);
        }
        ImportReport report = new ImportReport();
        report.caseId = pack.caseId;
        report.materialUid = materialUid;
        repo.transaction(() -> {
            // dictionaries first (all versions are retained)
            for (CasePack.DictVersionSpec dv : pack.dictionaries) {
                repo.insertDictionary(pack.caseId, toDictionary(dv));
            }
            for (CasePack.ChannelSpec ch : pack.channels) {
                repo.upsertChannel(pack.caseId,
                        new Records.ChannelConfig(ch.channel, ch.nominalKbps, ch.scheduleSlotMs,
                                ch.polarity == null ? "ODD" : ch.polarity));
            }
            // material envelope (original JSON preserved)
            repo.insertMaterial(materialUid, pack.caseId, "arinc429-capture", now,
                    Json.write(pack.material));

            List<Integer> seqs = new ArrayList<>();
            for (CasePack.WordSpec w : pack.material.words) {
                // parse to validate the 32-bit literal before writing anything
                int raw = ArincCodec.parseRawHex(w.raw);
                ArincCodec.bitString(raw);
                String uid = wordUid(materialUid, w.seq);
                Boolean importParity = w.importParity;
                repo.insertWord(new Records.Word(uid, materialUid, w.seq, w.epochMs, w.received,
                        w.channel, normalizeHex(w.raw), w.reversed, importParity,
                        pack.material.dictionaryVersion));
                report.wordUids.add(uid);
                seqs.add(w.seq);
            }
            report.wordCount = seqs.size();

            // dependency basis frozen for the baseline
            events.ensureMainBranch(pack.caseId, now);
            for (String dv : repo.listDictionaryVersions(pack.caseId)) {
                events.setDepBasis(pack.caseId, "dictionary", dv, dv, "GENESIS");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("materialUid", materialUid);
            payload.put("wordCount", report.wordCount);
            payload.put("dictionaryVersion", pack.material.dictionaryVersion);
            payload.put("preserved", List.of("seq", "received", "channel", "parity", "label",
                    "sdi", "ssm", "data", "dictionaryVersion"));
            Records.Event e = events.append(pack.caseId, EventLog.MAIN_BRANCH, "IMPORTED",
                    "imported " + report.wordCount + " raw words from " + pack.material.id,
                    payload, basis(pack.material.dictionaryVersion, "1", materialUid), 1, now);
            report.baselineEventUid = e.uid;
        });
        return report;
    }

    private void validate(CasePack pack) {
        if (pack.caseId == null || pack.caseId.isBlank()) {
            throw new Repository.ImportException("caseId is required");
        }
        if (pack.material == null || pack.material.words == null || pack.material.words.isEmpty()) {
            throw new Repository.ImportException("material with words is required");
        }
        if (pack.dictionaries == null || pack.dictionaries.isEmpty()) {
            throw new Repository.ImportException("at least one dictionary version is required");
        }
        List<Integer> seen = new ArrayList<>();
        for (CasePack.WordSpec w : pack.material.words) {
            if (w.raw == null || w.received == null || w.channel == null) {
                throw new Repository.ImportException("word seq " + w.seq
                        + " is missing raw/received/channel");
            }
            if (seen.contains(w.seq)) {
                throw new Repository.ImportException("duplicate in-place sequence: " + w.seq);
            }
            seen.add(w.seq);
            try {
                ArincCodec.parseRawHex(w.raw);
            } catch (IllegalArgumentException ex) {
                throw new Repository.ImportException(
                        "word seq " + w.seq + ": " + ex.getMessage());
            }
        }
    }

    private Records.DictionaryVersion toDictionary(CasePack.DictVersionSpec spec) {
        List<ArincCodec.LabelDef> defs = new ArrayList<>();
        for (CasePack.LabelSpec l : spec.labels) {
            defs.add(new ArincCodec.LabelDef(l.label, l.sdi, l.name, l.unit,
                    l.coordBase == null ? "" : l.coordBase,
                    ArincCodec.Encoding.valueOf(l.encoding), l.msb, l.lsb, l.resolution,
                    l.discrete));
        }
        return new Records.DictionaryVersion(spec.version,
                ArincCodec.SsmRuleSet.valueOf(spec.ssmRules == null ? "CLASSIC" : spec.ssmRules),
                spec.note == null ? "" : spec.note, defs);
    }

    static String normalizeHex(String raw) {
        String s = raw.trim().toLowerCase();
        if (!s.startsWith("0x")) {
            s = "0x" + s;
        }
        return s;
    }

    static String basis(String dictionaryVersion, String paramsVersion, String materialUid) {
        return StableId.hash("basis", dictionaryVersion, paramsVersion, materialUid);
    }

    public static String wordUid(String materialUid, int seq) {
        return StableId.of("word", materialUid, Integer.toString(seq));
    }
}
