package avdata.arinc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application orchestration: load example, import, replay script steps, branch rulings,
 *  revocation, export and provenance lookup. */
public final class AppService {

    public static final String RESOURCE_CASEPACK = "/casepack/arinc429-evidence.json";

    private final Db db;
    private final Repository repo;
    private final EventLog events;
    private final DerivedEngine engine;
    private String caseId;
    private String materialUid;
    private boolean seeded;

    public AppService(Db db) {
        this.db = db;
        this.repo = new Repository(db);
        this.events = new EventLog(db);
        this.engine = new DerivedEngine(repo, events);
    }

    public static String bundledCasePackJson() {
        try {
            return new String(AppService.class.getResourceAsStream(RESOURCE_CASEPACK)
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("bundled case pack missing", e);
        }
    }

    public synchronized boolean isSeeded() {
        return seeded;
    }

    /** Load the repository example inside one atomic import. Re-running is a no-op. */
    public synchronized Map<String, Object> loadExample() {
        return importJson(bundledCasePackJson(), System.currentTimeMillis());
    }

    public synchronized Map<String, Object> importJson(String json, long now) {
        CasePack pack = CasePack.parse(json);
        if (repo.listMaterials(pack.caseId).stream().findAny().isPresent()) {
            this.caseId = pack.caseId;
            this.materialUid = repo.listMaterials(pack.caseId).get(0);
            this.seeded = true;
            return Map.of("caseId", caseId, "materialUid", materialUid, "alreadyImported", true);
        }
        Importer.ImportReport report = new Importer(repo, events).importPack(pack, now);
        this.caseId = report.caseId;
        this.materialUid = report.materialUid;
        this.seeded = true;
        return Map.of("caseId", caseId, "materialUid", materialUid, "wordCount",
                report.wordCount, "baselineEventUid", report.baselineEventUid);
    }

    public synchronized String caseId() {
        return caseId;
    }

    public synchronized String materialUid() {
        return materialUid;
    }

    public DerivedEngine.Snapshot snapshot(String branchId, String atEventUid) {
        String b = branchId == null || branchId.isBlank() ? EventLog.MAIN_BRANCH : branchId;
        return engine.snapshot(caseId, b, atEventUid);
    }

    public List<EventLog.BranchInfo> branches() {
        return events.listBranches(caseId);
    }

    // ------------------------------------------------------------------
    // review actions - each creates a new event; source data is never rewritten
    // ------------------------------------------------------------------

    public static final class ActionException extends RuntimeException {
        public final String code;

        public ActionException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private Records.Event append(String branchId, String type, String summary,
                                 Map<String, Object> payload, int expectedRev, long now) {
        DerivedEngine.Snapshot s = snapshot(branchId, null);
        String basis = s.depBasis;
        try {
            return events.append(caseId, branchId, type, summary, payload, basis, expectedRev, now);
        } catch (EventLog.Conflict c) {
            throw new ActionException("CONFLICT", c.getMessage() + "\n" + c.diff);
        }
    }

    public synchronized Map<String, Object> ruling(String branchId, String targetUid,
                                                   String candidate, boolean accepted,
                                                   String note, int expectedRev, long now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetUid", targetUid);
        payload.put("candidate", candidate);
        payload.put("accepted", accepted);
        payload.put("note", note == null ? "" : note);
        Records.Event e = append(branchId, "RULING",
                (accepted ? "accepted " : "rejected ") + candidate + " on " + targetUid,
                payload, expectedRev, now);
        return Map.of("eventUid", e.uid, "newRev", expectedRev + 1);
    }

    public synchronized Map<String, Object> revoke(String branchId, String eventUid, int expectedRev,
                                                   long now) {
        Records.Event target = events.event(eventUid);
        if (target == null || !"RULING".equals(target.type)) {
            throw new ActionException("NOT_FOUND", "ruling event not found: " + eventUid);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revokesEvent", eventUid);
        payload.put("targetUid", target.payload.get("targetUid"));
        payload.put("candidate", target.payload.get("candidate"));
        Records.Event e = append(branchId, "RULING_REVOKED",
                "revoked ruling " + eventUid + "; previous branch state restorable",
                payload, expectedRev, now);
        return Map.of("eventUid", e.uid, "newRev", expectedRev + 1);
    }

    public synchronized Map<String, Object> reviseParams(String branchId, String paramsVersion,
                                                         double highMs, double lowMs,
                                                         int expectedRev, long now) {
        if (highMs <= 0 || lowMs <= 0) {
            throw new ActionException("BAD_PARAMS", "schedule slots must be positive");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paramsVersion", paramsVersion);
        payload.put("highScheduleMs", highMs);
        payload.put("lowScheduleMs", lowMs);
        Records.Event e = append(branchId, "PARAM_REVISION",
                "parameter revision " + paramsVersion + " (high " + highMs + "ms, low " + lowMs
                        + "ms); rate-derived scope invalidated",
                payload, expectedRev, now);
        events.setDepBasis(caseId, DerivedEngine.SCOPE_PARAMS, "schedules", paramsVersion, e.uid);
        return Map.of("eventUid", e.uid, "newRev", expectedRev + 1);
    }

    public synchronized Map<String, Object> reviseDictionary(String branchId, String version,
                                                             int expectedRev, long now) {
        if (repo.loadDictionary(caseId, version) == null) {
            throw new ActionException("NOT_FOUND", "unknown dictionary version: " + version);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dictionaryVersion", version);
        Records.Event e = append(branchId, "DICT_REVISION",
                "dictionary version set to " + version
                        + "; BNR/BCD/discrete interpretation changes, bits do not",
                payload, expectedRev, now);
        events.setDepBasis(caseId, "dictionary", "active", version, e.uid);
        return Map.of("eventUid", e.uid, "newRev", expectedRev + 1);
    }

    /** Apply the single-source patch as an event. The raw source row is never changed; the
     *  patched bits live only on the derived branch and the dependency scope is explicit. */
    public synchronized Map<String, Object> sourcePatch(String branchId, int seq, String rawHex,
                                                        int expectedRev, long now) {
        int validated = ArincCodec.parseRawHex(rawHex); // fail before any write
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", seq);
        payload.put("rawHex", String.format("0x%08X", validated));
        payload.put("materialUid", materialUid);
        Records.Event e = append(branchId, "SOURCE_PATCH",
                "single-source patch on seq " + seq + "; invalidation scope limited to that word, "
                        + "adjacent intervals and its label aggregate",
                payload, expectedRev, now);
        events.setDepBasis(caseId, DerivedEngine.SCOPE_PATCH, "word:" + seq,
                "patch@" + e.uid, e.uid);
        return Map.of("eventUid", e.uid, "newRev", expectedRev + 1);
    }

    public synchronized Map<String, Object> createBranch(String name, String baseEventUid,
                                                         long now) {
        DerivedEngine.Snapshot s = snapshot(EventLog.MAIN_BRANCH, null);
        String base = baseEventUid == null || baseEventUid.isBlank()
                ? s.atEventUid : baseEventUid;
        String uid = events.createBranch(caseId, name, EventLog.MAIN_BRANCH, base, now);
        return Map.of("branchUid", uid, "baseEvent", base);
    }

    // ------------------------------------------------------------------
    // provenance & export
    // ------------------------------------------------------------------

    /** Trace every material/rule/decision that contributed to a displayed object uid. */
    public synchronized Map<String, Object> provenance(String objectUid, String branchId,
                                                       String atEventUid) {
        DerivedEngine.Snapshot s = snapshot(branchId, atEventUid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objectUid", objectUid);
        out.put("depBasis", s.depBasis);
        out.put("dictionaryVersion", s.activeDictionaryVersion);
        out.put("paramsVersion", s.params.version);
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Records.Event e : s.events) {
            if (mentions(e, objectUid)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventUid", e.uid);
                row.put("type", e.type);
                row.put("summary", e.summary);
                row.put("epochMs", e.epochMs);
                row.put("depBasis", e.depBasis);
                chain.add(row);
            }
        }
        out.put("events", chain);
        return out;
    }

    private boolean mentions(Records.Event e, String objectUid) {
        String payload = Json.write(e.payload);
        if (payload.contains(stripPrefix(objectUid))) {
            return true;
        }
        if (objectUid.startsWith("word:")) {
            String seq = objectUid.substring("word:".length());
            return payload.contains("\"seq\":" + seq);
        }
        if (objectUid.startsWith("segment:")) {
            return payload.contains(objectUid.substring("segment:".indexOf('-')));
        }
        return false;
    }

    private String stripPrefix(String uid) {
        int colon = uid.indexOf(':');
        return colon < 0 ? uid : uid.substring(colon + 1);
    }

    /** Deterministic export: the case pack plus the event journal. Re-importing into a fresh
     *  database preserves every uid, order and provenance link. */
    public synchronized Map<String, Object> exportState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "arinc429-review-export/1");
        out.put("caseId", caseId);
        out.put("casePack", Json.read(repo.materialContent(materialUid) == null
                ? bundledCasePackJson() : bundledCasePackJson()));
        List<Map<String, Object>> evs = new ArrayList<>();
        for (Records.Event e : events.ancestry(EventLog.MAIN_BRANCH)) {
            evs.add(eventToMap(e));
        }
        out.put("events", evs);
        out.put("branches", branchList());
        return out;
    }

    private List<Map<String, Object>> branchList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EventLog.BranchInfo b : branches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uid", b.uid);
            row.put("name", b.name);
            row.put("parentBranch", b.parentBranch);
            row.put("baseEvent", b.baseEvent);
            row.put("headEvent", b.headEvent);
            row.put("rev", b.rev);
            for (Records.Event e : events.eventsOnBranch(b.uid)) {
                if (!EventLog.MAIN_BRANCH.equals(b.uid)) {
                    out.add(row);
                    break;
                }
            }
            if (EventLog.MAIN_BRANCH.equals(b.uid)) {
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, Object> eventToMap(Records.Event e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uid", e.uid);
        row.put("epochMs", e.epochMs);
        row.put("branchId", e.branchId);
        row.put("type", e.type);
        row.put("parentEvent", e.parentEventId);
        row.put("summary", e.summary);
        row.put("payload", e.payload);
        row.put("prevHash", e.prevHash);
        row.put("selfHash", e.selfHash);
        row.put("depBasis", e.depBasis);
        return row;
    }

    /** Replay an exported journal deterministically (used after delete + reimport). Uids are
     *  content-addressed, so replay reproduces them byte for byte. */
    public synchronized void replayJournal(List<Map<String, Object>> journal, long now) {
        for (Map<String, Object> row : journal) {
            String type = (String) row.get("type");
            String branchId = (String) row.get("branchId");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) row.get("payload");
            EventLog.BranchInfo info = events.branchInfo(branchId);
            int rev = info == null ? 1 : info.rev + 1;
            events.append(caseId, branchId, type, (String) row.get("summary"), payload,
                    (String) row.get("depBasis"), info == null ? 1 : info.rev,
                    row.get("epochMs") instanceof Number n ? n.longValue() : now);
            // rev was already advanced
        }
    }

    public String dbPathHint(Path path) {
        return path.toAbsolutePath().toString();
    }

    public EventLog eventLog() {
        return events;
    }
}
