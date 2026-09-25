package avdata.a429.service;

import avdata.a429.decode.*;
import avdata.a429.store.Ids;
import avdata.a429.store.Repository;

import java.util.*;

/**
 * Application orchestration.
 *
 * <ul>
 *   <li>imports are transactional: a failure rolls the whole batch back;</li>
 *   <li>sources and interpretations are stored separately, never cleaned in place;</li>
 *   <li>derivation is event-sourced; each event records dependency refs;</li>
 *   <li>reference/source changes invalidate only the dependent scope;</li>
 *   <li>decisions are branches with optimistic conflict detection and undo.</li>
 * </ul>
 */
public final class ReviewService {
    private final Repository repo;

    public ReviewService(Repository repo) {
        this.repo = repo;
    }

    private long now() {
        return System.currentTimeMillis() * 1000L;
    }

    // ================= import =================

    /**
     * Atomic import. {@code wordsIn} keeps the original representation strings;
     * any failure rolls back so no partial batch survives.
     */
    public String importWords(String sourceName, String channel, String dictVersion,
                              String configVersion, List<Models.RawWordIn> wordsIn,
                              String note) {
        String batchId = Ids.hash("b",
                sourceName + "|" + channel + "|" + dictVersion + "|" + wordsIn.size()
                        + "|" + (wordsIn.isEmpty() ? "" : wordsIn.get(0).receivedAtUs())
                        + "|" + (wordsIn.isEmpty() ? "" : wordsIn.get(wordsIn.size() - 1).rawBits()));
        try {
            long t = now();
            repo.insertBatch(batchId, t, sourceName, channel, dictVersion, configVersion, note);
            for (Models.RawWordIn w : wordsIn) {
                String wid = Ids.wordId(batchId, w.seq());
                repo.insertWord(wid, batchId, w.seq(), w.receivedAtUs(), w.channel(),
                        w.rawBits(), w.rawRepr());
            }
            repo.commit();
        } catch (RuntimeException e) {
            repo.rollback();
            throw e;
        }
        return batchId;
    }

    /** Import an additional reference dictionary version (no words changed). */
    public String addDictionary(String version, String payloadJson) {
        repo.insertDictionary(version, payloadJson, now());
        repo.commit();
        return version;
    }

    /** Import a new channel config version (parameter revision). */
    public String addChannelConfig(String versionId, String convention, String note) {
        repo.insertChannelConfig(versionId, convention, note, now());
        repo.commit();
        return versionId;
    }

    // ================= branches / derivation =================

    public String createBranch(String name, String parentBranchId) {
        String branchId = Ids.hash("br", name + "|" + now() + "|" + UUID.randomUUID());
        repo.insertBranch(branchId, parentBranchId, name, now());
        if (parentBranchId == null) {
            // trunk holds the baseline derivation
            deriveBaseline(branchId);
        }
        repo.commit();
        return branchId;
    }

    private String appendEvent(String branchId, String kind, String payload,
                               String actor, String rationale, List<Models.DepView> deps) {
        long t = now();
        long seq = repo.nextEventSeq(branchId);
        Models.BranchView b = repo.branch(branchId);
        String parent = b.tipEventId();
        String eventId = Ids.hash("e", branchId + "#" + seq + "#" + kind + "#" + payload + "#" + t);
        repo.insertEvent(eventId, branchId, seq, parent, kind, t, actor, payload, rationale, deps);
        repo.setBranchTip(branchId, eventId);
        return eventId;
    }

    private record PendingArtifact(String kind, String identity, String status,
                                   String targetRef, String payload, List<Models.DepView> deps) {}

    private void persist(String branchId, String eventId, List<PendingArtifact> pendings) {
        for (PendingArtifact pa : pendings) {
            String aid = Ids.hash("art", pa.identity() + "|" + eventId);
            repo.insertArtifact(aid, pa.identity(), branchId, eventId, pa.kind(),
                    pa.status(), pa.targetRef(), pa.payload());
        }
    }

    /** Baseline: decode every word and build rate segments, all candidates retained. */
    public String deriveBaseline(String branchId) {
        List<Models.DepView> deps = new ArrayList<>();
        List<PendingArtifact> pendings = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> batchIds = repo.allBatchIds();
        for (String batchId : batchIds) {
            deps.add(new Models.DepView("BATCH", batchId));
            collectBatch(branchId, batchId, pendings, deps);
            summary.put(batchId, Json.map(repo.batchInfo(batchId)));
        }
        String payload = Json.obj("batches", summary);
        String eventId = appendEvent(branchId, "BASELINE_DERIVED", payload,
                "system", "initial structural decode and rate segmentation for all imported words", deps);
        persist(branchId, eventId, pendings);
        repo.commit();
        return eventId;
    }

    // ================= collection per batch =================

    private void collectBatch(String branchId, String batchId,
                              List<PendingArtifact> pendings, List<Models.DepView> deps) {
        List<Models.WordView> words = repo.wordsOfBatch(batchId);
        String dictVersion = Json.map(repo.batchInfo(batchId)).get("dictVersion").toString();
        String dictPayload = repo.dictionaryPayload(dictVersion);
        LabelDictionary dict = DictionaryIo.parse(dictVersion, dictPayload);
        deps.add(new Models.DepView("DICT", dictVersion));

        for (Models.WordView w : words) {
            collectWord(branchId, w, dict, dictVersion, pendings, deps);
        }
        collectRates(branchId, batchId, words, pendings, deps);
        collectAggregates(branchId, batchId, words, dict, dictVersion, pendings, deps);
    }

    private void collectWord(String branchId, Models.WordView w, LabelDictionary dict,
                             String dictVersion, List<PendingArtifact> pendings,
                             List<Models.DepView> deps) {
        DecodedWord d = Decoder.decode(w.rawBits(), dict);
        List<Models.DepView> wd = new ArrayList<>(List.of(
                new Models.DepView("WORD", w.wordId()),
                new Models.DepView("DICT", dictVersion)));

        Map<String, Object> struct = new LinkedHashMap<>();
        struct.put("wordId", w.wordId());
        struct.put("seq", w.seq());
        struct.put("receivedAtUs", w.receivedAtUs());
        struct.put("channel", w.channel());
        struct.put("rawRepr", w.rawRepr());
        struct.put("hex", w.hex());
        struct.put("bits", w.bits());
        struct.put("parityValid", d.parityValid);
        struct.put("labelBits", String.format("%8s", Integer.toBinaryString(d.labelBits & 0xFF)).replace(' ', '0'));
        struct.put("labelOctalTransmission", d.labelOctalTransmission);
        struct.put("labelOctalDocument", d.labelOctalDocument);
        struct.put("sdi", d.sdi);
        struct.put("ssm", d.ssm);
        struct.put("dataField", String.format("%19s", Integer.toBinaryString(d.dataField)).replace(' ', '0'));
        struct.put("dictionaryVersion", dictVersion);
        struct.put("reversalCandidateOctal",
                Bits.octal3(Bits.reverse8(d.labelBits)));

        List<Map<String, Object>> interpList = new ArrayList<>();
        for (Interpretation it : d.interpretations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("encoding", it.encoding);
            m.put("parameter", it.parameterName);
            m.put("unit", it.unit);
            m.put("basis", it.coordinateBasis);
            m.put("value", it.value);
            m.put("convertible", it.isConvertible());
            m.put("rawText", it.rawText);
            m.put("labelOctal", it.labelOctal);
            m.put("labelConvention", it.labelConvention);
            m.put("sdi", it.sdi);
            m.put("ssm", it.ssm);
            m.put("ssmMeaning", it.ssmMeaning);
            m.put("discreteStates", it.discreteStates);
            m.put("diagnostics", it.diagnostics.stream().map(x -> {
                Map<String, String> dm = new LinkedHashMap<>();
                dm.put("code", x.code());
                dm.put("message", x.message());
                return dm;
            }).toList());
            m.put("dictionaryVersion", it.dictionaryVersion);
            interpList.add(m);
        }
        struct.put("interpretations", interpList);
        struct.put("candidateCount", interpList.size());

        String identity = Ids.artifactIdentity("WORDDECODE", w.wordId(), "");
        pendings.add(new PendingArtifact("WORDDECODE", identity, "ACTIVE",
                w.wordId(), Json.to(struct), wd));
        deps.addAll(wd);
    }

    private void collectRates(String branchId, String batchId, List<Models.WordView> words,
                              List<PendingArtifact> pendings, List<Models.DepView> deps) {
        long[] ts = words.stream().mapToLong(Models.WordView::receivedAtUs).toArray();
        List<RateSegmenter.Gap> gaps = RateSegmenter.gaps(ts);
        List<Map<String, Object>> gapViews = new ArrayList<>();
        for (RateSegmenter.Gap g : gaps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fromSeq", words.get(g.fromIndex()).seq());
            m.put("toSeq", words.get(g.toIndex()).seq());
            m.put("dtUs", g.dtUs());
            m.put("instantHz", round(g.hz()));
            gapViews.add(m);
        }
        List<RateSegmenter.Segment> segments = RateSegmenter.segments(ts);
        List<Map<String, Object>> segViews = new ArrayList<>();
        for (RateSegmenter.Segment s : segments) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startSeq", words.get(s.startIndex()).seq());
            m.put("endSeq", words.get(Math.min(s.endIndex(), words.size() - 1)).seq());
            m.put("spanUs", s.dtUs());
            m.put("measuredHz", round(s.measuredHz()));
            m.put("candidateBps", s.candidateBps());
            m.put("reason", s.reason());
            segViews.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("gaps", gapViews);
        payload.put("segments", segViews);
        payload.put("standardBps", RateSegmenter.STANDARD_BPS);

        List<Models.DepView> rd = new ArrayList<>();
        for (Models.WordView w : words) {
            rd.add(new Models.DepView("WORD", w.wordId()));
        }
        String identity = Ids.artifactIdentity("RATE", batchId, "");
        pendings.add(new PendingArtifact("RATE", identity, "ACTIVE",
                batchId, Json.to(payload), rd));
        deps.addAll(rd);
    }

    private void collectAggregates(String branchId, String batchId, List<Models.WordView> words,
                                   LabelDictionary dict, String dictVersion,
                                   List<PendingArtifact> pendings, List<Models.DepView> deps) {
        // group engineering values per parameter; bad parity and invalid SSM never enter
        Map<String, List<Double>> acc = new LinkedHashMap<>();
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        for (Models.WordView w : words) {
            DecodedWord d = Decoder.decode(w.rawBits(), dict);
            if (!d.parityValid) {
                continue;
            }
            for (Interpretation it : d.interpretations) {
                if (it.value == null || Ssm.isInvalidState(it.encoding, it.ssm)) {
                    continue;
                }
                String key = it.parameterName + "@" + dictVersion + "@" + it.encoding
                        + "@" + it.labelConvention + "@" + it.labelOctal;
                acc.computeIfAbsent(key, k -> new ArrayList<>()).add(it.value);
                meta.computeIfAbsent(key, k -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("parameter", it.parameterName);
                    mm.put("unit", it.unit);
                    mm.put("basis", it.coordinateBasis);
                    mm.put("encoding", it.encoding);
                    mm.put("dictionaryVersion", dictVersion);
                    mm.put("labelOctal", it.labelOctal);
                    mm.put("labelConvention", it.labelConvention);
                    return mm;
                });
            }
        }
        for (Map.Entry<String, List<Double>> e : acc.entrySet()) {
            List<Double> values = e.getValue();
            double sum = values.stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Object> p = new LinkedHashMap<>(meta.get(e.getKey()));
            p.put("batchId", batchId);
            p.put("count", values.size());
            p.put("min", round(values.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
            p.put("max", round(values.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
            p.put("mean", round(sum / values.size()));
            List<Models.DepView> ad = new ArrayList<>();
            ad.add(new Models.DepView("BATCH", batchId));
            ad.add(new Models.DepView("DICT", dictVersion));
            String identity = Ids.artifactIdentity("AGGREGATE", batchId, e.getKey());
            pendings.add(new PendingArtifact("AGGREGATE", identity, "ACTIVE",
                    batchId, Json.to(p), ad));
            deps.addAll(ad);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    // ================= invalidation scope =================

    /**
     * Recompute only artifacts depending on {@code depType:depRef} after a source
     * or reference change. The event records the precise affected identities so the
     * scope is visible; nothing else is silently replaced.
     */
    public String invalidateScope(String branchId, String depType, String depRef,
                                  String reason, String actor) {
        List<String> affectedEvents = repo.eventsDependingOn(depType, depRef);
        // collect affected artifact identities transitively through their producing events
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(affectedEvents);
        while (!stack.isEmpty()) {
            String eid = stack.pop();
            if (!visited.add(eid)) {
                continue;
            }
            for (Models.ArtifactView a : repo.artifactsProducedBy(eid)) {
                identities.add(a.identity());
            }
            // downstream artifacts that depend on those artifacts via EVENT edges
            for (Models.DepView d : repo.depsOf(eid)) {
                if (d.depType().equals("EVENT")) {
                    stack.push(d.depRef());
                }
            }
        }
        List<Models.DepView> deps = new ArrayList<>();
        deps.add(new Models.DepView(depType, depRef));
        String payload = Json.obj("depType", depType, "depRef", depRef,
                "affectedArtifactCount", identities.size(),
                "affectedArtifacts", identities,
                "affectedEvents", affectedEvents);
        String eventId = appendEvent(branchId, "DEPENDENCY_INVALIDATED", payload,
                actor, reason, deps);
        repo.invalidateArtifactsOfEvent(branchId, eventId, new ArrayList<>(identities));
        repo.commit();
        return eventId;
    }

    /**
     * Localized source change: re-import one corrected word into a new batch on a
     * branch, then invalidate only the artifacts of that word plus rate/aggregate
     * of its batch.
     */
    public String recordSourcePatch(String branchId, String oldWordId, Models.RawWordIn replacement,
                                    String newBatchId, String dictVersion, String configVersion,
                                    String sourceName, String channel, String rationale) {
        try {
            repo.insertBatch(newBatchId, now(), sourceName + "#patch", channel,
                    dictVersion, configVersion, "single direct-source change");
            repo.insertWord(Ids.wordId(newBatchId, replacement.seq()), newBatchId,
                    replacement.seq(), replacement.receivedAtUs(), replacement.channel(),
                    replacement.rawBits(), replacement.rawRepr());
        } catch (RuntimeException e) {
            repo.rollback();
            throw e;
        }
        List<Models.DepView> deps = List.of(
                new Models.DepView("WORD", oldWordId),
                new Models.DepView("WORD", Ids.wordId(newBatchId, replacement.seq())));
        String payload = Json.obj("oldWordId", oldWordId,
                "newWordId", Ids.wordId(newBatchId, replacement.seq()),
                "change", "single direct source bit/value altered",
                "scope", "decode of one word; rate/aggregate of its channel batch recomputed");
        String eventId = appendEvent(branchId, "SOURCE_PATCHED", payload,
                "reviewer", rationale, deps);
        repo.commit();
        invalidateScope(branchId, "WORD", oldWordId,
                "recompute decode/rate/aggregate affected by one changed source word", "reviewer");
        return eventId;
    }

    // ================= human decisions (adjudication) =================

    /**
     * Accept / reject / adjust a candidate artifact. {@code baseEventId} is the tip
     * the reviewer saw; if the branch moved meanwhile a conflict result is returned
     * with both sides' diff and nothing is written.
     */
    public synchronized Map<String, Object> decide(String branchId, String action,
                                                   String targetIdentity, String baseEventId,
                                                   Map<String, Object> params, String actor,
                                                   String rationale) {
        Models.BranchView branch = repo.branch(branchId);
        if (branch == null) {
            throw new IllegalArgumentException("unknown branch " + branchId);
        }
        String currentTip = branch.tipEventId();
        if (baseEventId != null && !baseEventId.equals(currentTip)) {
            return conflict(branchId, baseEventId, currentTip, targetIdentity);
        }
        String payload = Json.obj("action", action, "targetIdentity", targetIdentity,
                "params", params == null ? Map.of() : params);
        List<Models.DepView> deps = new ArrayList<>();
        deps.add(new Models.DepView("EVENT", baseEventId == null ? "" : baseEventId));
        deps.add(new Models.DepView("ARTIFACT", targetIdentity));
        String eventId = appendEvent(branchId, "DECISION_" + action, payload, actor,
                rationale == null ? "" : rationale, deps);
        String decisionId = Ids.hash("dec", eventId);
        repo.insertDecision(decisionId, eventId, branchId, action, targetIdentity,
                baseEventId == null ? "" : baseEventId, Json.to(params == null ? Map.of() : params));
        if ("REJECT".equals(action)) {
            repo.rejectArtifacts(branchId, eventId, List.of(targetIdentity));
        } else {
            // ACCEPT / ADJUST supersede prior status by emitting a verdict artifact
            Map<String, Object> vp = new LinkedHashMap<>();
            vp.put("targetIdentity", targetIdentity);
            vp.put("verdict", action);
            vp.put("params", params == null ? Map.of() : params);
            String identity = Ids.artifactIdentity("VERDICT", targetIdentity, action);
            String aid = Ids.hash("art", identity + "|" + eventId);
            repo.insertArtifact(aid, identity, branchId, eventId, "VERDICT",
                    "ACTIVE", targetIdentity, Json.to(vp));
        }
        repo.commit();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("conflict", false);
        ok.put("eventId", eventId);
        ok.put("newTip", eventId);
        return ok;
    }

    /** Undo a decision: appends an UNDO event and restores the previous branch state. */
    public synchronized String undoDecision(String branchId, String decisionEventId, String actor) {
        String decisionJson = repo.decisionForEvent(decisionEventId);
        if (decisionJson == null) {
            throw new IllegalArgumentException("not a decision event: " + decisionEventId);
        }
        Map<String, Object> d = Json.map(decisionJson);
        Models.EventView ev = repo.event(decisionEventId);
        Models.BranchView branch = repo.branch(branchId);
        if (!branch.tipEventId().equals(decisionEventId)) {
            throw new IllegalStateException("can only undo the latest decision on this branch");
        }
        String payload = Json.obj("undoOf", decisionEventId,
                "restoredParent", ev.parentEventId(),
                "targetIdentity", d.get("targetIdentity"));
        List<Models.DepView> deps = List.of(
                new Models.DepView("EVENT", decisionEventId),
                new Models.DepView("ARTIFACT", String.valueOf(d.get("targetIdentity"))));
        String undoId = appendEvent(branchId, "DECISION_UNDONE", payload, actor,
                "reviewer revoked the adjudication; previous branch conclusion restored", deps);
        repo.markDecisionUndone(decisionEventId, undoId);
        // restore artifact state produced/changed by the undone event
        repo.invalidateArtifactsOfEvent(branchId, undoId,
                List.of(Ids.artifactIdentity("VERDICT", String.valueOf(d.get("targetIdentity")),
                        String.valueOf(d.get("action")))));
        if ("REJECT".equals(d.get("action"))) {
            // rejected artifact returns to active as seen at the parent event
            String target = String.valueOf(d.get("targetIdentity"));
            restoreRejected(branchId, target, undoId);
        }
        repo.commit();
        return undoId;
    }

    private void restoreRejected(String branchId, String identity, String undoEventId) {
        try (var ps = repo.connection().prepareStatement(
                "UPDATE artifact SET status='ACTIVE', invalidated_by_event=? "
                        + "WHERE identity=? AND branch_id=? AND status='REJECTED'")) {
            ps.setString(1, undoEventId);
            ps.setString(2, identity);
            ps.setString(3, branchId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> conflict(String branchId, String submittedBase,
                                         String currentTip, String targetIdentity) {
        List<Models.EventView> mine = eventsAfter(branchId, submittedBase);
        List<Map<String, Object>> diff = new ArrayList<>();
        for (Models.EventView e : mine) {
            diff.add(Map.of("eventId", e.eventId(), "kind", e.kind(),
                    "actor", e.actor(), "payload", e.payload()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("conflict", true);
        r.put("message", "branch advanced since your view; rebase onto the current tip");
        r.put("submittedBase", submittedBase);
        r.put("currentTip", currentTip);
        r.put("targetIdentity", targetIdentity);
        r.put("theirEvents", diff);
        return r;
    }

    private List<Models.EventView> eventsAfter(String branchId, String eventId) {
        List<Models.EventView> all = repo.eventsOfBranch(branchId);
        List<Models.EventView> out = new ArrayList<>();
        boolean take = false;
        for (Models.EventView e : all) {
            if (take) {
                out.add(e);
            }
            if (e.eventId().equals(eventId)) {
                take = true;
            }
        }
        return out;
    }

    // ================= history read-back =================

    /** Reconstruct the visible conclusion at any past event without overwriting it. */
    public Map<String, Object> snapshotAt(String branchId, String eventId) {
        List<Models.EventView> events = repo.eventsOfBranch(branchId);
        List<Models.EventView> upTo = new ArrayList<>();
        for (Models.EventView e : events) {
            upTo.add(e);
            if (e.eventId().equals(eventId)) {
                break;
            }
        }
        List<Models.ArtifactView> artifacts = repo.artifactsAt(branchId, eventId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branchId);
        out.put("atEvent", eventId);
        out.put("events", upTo);
        out.put("artifacts", artifacts);
        return out;
    }

    public List<Models.EventView> events(String branchId) {
        return repo.eventsOfBranch(branchId);
    }

    public List<Models.BranchView> branches() {
        return repo.branches();
    }

    public Models.BranchView branch(String id) {
        return repo.branch(id);
    }

    public List<Models.WordView> batchWords(String batchId) {
        return repo.wordsOfBatch(batchId);
    }

    public List<String> batches() {
        return repo.allBatchIds();
    }

    public String batchInfo(String batchId) {
        return repo.batchInfo(batchId);
    }

    public List<String> dictVersions() {
        return repo.dictionaryVersions();
    }

    // ================= export / import =================

    public Map<String, Object> exportAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "a429-evidence-archive");
        out.put("formatVersion", 1);
        out.put("meta", Map.of("exportedAtUs", now()));
        out.put("dictionaryVersions", repo.dictionaryVersions());
        out.put("batchIds", repo.allBatchIds());
        out.put("branches", repo.branches());
        List<Map<String, Object>> eventRows = new ArrayList<>();
        for (Models.BranchView b : repo.branches()) {
            for (Models.EventView e : repo.eventsOfBranch(b.branchId())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("event", e);
                row.put("decision", repo.decisionForEvent(e.eventId()));
                eventRows.add(row);
            }
        }
        out.put("events", eventRows);
        return out;
    }

    public Repository repository() {
        return repo;
    }
}
