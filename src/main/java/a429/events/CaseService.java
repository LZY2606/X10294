package a429.events;

import a429.casepack.CasePack;
import a429.casepack.CasePackLoader;
import a429.casepack.PackFragment;
import a429.casepack.PackWord;
import a429.casepack.SourcePatch;
import a429.domain.RawWord;
import a429.store.EventStore;
import a429.store.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates import, branch replay, adjudication and export.
 *
 * Every mutation appends one event carrying:
 *  depends  - exact dependency versions used (channel config, dict version,
 *             word revision, rate parameters)
 *  invalidates - entities whose prior derived conclusions were superseded,
 *             with the dependency edge and rule.
 * No import-time irreversible cleaning occurs: reversed/illegal/bad-parity
 * words are all stored as captured and diagnosed at the derived layer only.
 */
public final class CaseService {

    private final EventStore store;
    private final ReplayEngine replay = new ReplayEngine();
    private final ViewProjector projector = new ViewProjector();
    private final Map<String, CasePack> packs = new LinkedHashMap<>();

    public CaseService(EventStore store) {
        this.store = store;
    }

    public CasePack loadBundledPack() {
        CasePack pack = new CasePackLoader().loadFromResources();
        packs.put(pack.caseId(), pack);
        return pack;
    }

    public synchronized CasePack ensureCaseFromBundledPack() {
        CasePack pack = loadBundledPack();
        store.createCaseIfAbsent(pack.caseId(), pack.title(), CasePackLoader.PACK_DIR);
        store.createBranch(pack.caseId(), "main", null, "main", -1);
        if (store.events(pack.caseId(), "main").isEmpty()) {
            importInitialFragments(pack, "main", null);
        }
        return pack;
    }

    public CasePack packFor(String caseId) {
        return packs.computeIfAbsent(caseId, id -> {
            CasePack p = loadBundledPack();
            if (!p.caseId().equals(id)) {
                throw new IllegalArgumentException("unknown case " + id);
            }
            return p;
        });
    }

    public FoldView view(String caseId, String branchId) {
        return view(caseId, branchId, Integer.MAX_VALUE);
    }

    public FoldView view(String caseId, String branchId, int atSeq) {
        CasePack pack = packFor(caseId);
        List<Event> events = store.events(caseId, branchId);
        FoldState state = replay.foldTo(pack, events, atSeq);
        return projector.project(pack, state, events.stream().filter(e -> e.seq() <= atSeq).toList());
    }

    private void importInitialFragments(CasePack pack, String branchId, Integer baseRevision) {
        for (PackFragment fragment : pack.fragments()) {
            appendFragmentEvent(pack, branchId, fragment.fragmentId(), fragment.words(),
                    baseRevision, "initial");
        }
    }

    public Event appendFragmentEvent(CasePack pack, String branchId, String fragmentId,
                                     List<PackWord> words, Integer baseRevision, String phase) {
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("fragmentId", fragmentId);
        payload.put("phase", phase);
        ArrayNode arr = payload.putArray("words");
        for (PackWord w : words) {
            ObjectNode wn = arr.addObject();
            wn.put("wordId", w.wordId());
            wn.put("channelId", w.channelId());
            wn.put("position", w.position());
            wn.put("receivedAtNs", w.receivedAtNs());
            wn.put("receivedAtIso", w.receivedAtIso());
            wn.put("wireHex", w.wireHex());
            wn.put("note", w.note());
        }
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("channels", "pack:" + pack.packId(), "bit order and nominal speed"));
        depends.add(dep("dictionary", pack.defaultDictVersion(), "initial interpretation version"));
        ArrayNode invalidates = Json.MAPPER.createArrayNode();
        Event event = store.append(pack.caseId(), branchId, headEventId(pack.caseId(), branchId),
                "FRAGMENT_IMPORTED", payload, depends, invalidates, "source", tags("import"));
        store.insertFragment(pack.caseId(), fragmentId, words.stream()
                .map(w -> new RawWord(w.wordId(), w.channelId(), w.position(),
                        w.receivedAtNs(), w.receivedAtIso(), w.wireHex(), fragmentId))
                .toList());
        return event;
    }

    public synchronized Event reviseRateParams(String caseId, String branchId,
                                               Double gapFactor, Double switchRatio,
                                               Integer baseRevision) {
        CasePack pack = packFor(caseId);
        FoldView before = view(caseId, branchId);
        ObjectNode payload = Json.MAPPER.createObjectNode();
        if (gapFactor != null) {
            payload.put("gapFactor", gapFactor);
        }
        if (switchRatio != null) {
            payload.put("switchRatio", switchRatio);
        }
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("rateParams", "gapFactor=" + before.gapFactor() + ",switchRatio="
                + before.switchRatio(), "previous segmentation parameters"));
        Invalidation inv = new Invalidation(
                "rate segmentation depends on rate parameters",
                "rateParams",
                rateEntityIds(before),
                List.of("rateParams", "intervalMedian", "boundary", "candidate"),
                "rate-entities-only");
        return store.append(caseId, branchId, before.lastEventId(),
                "RATE_PARAMS_REVISED", payload, depends, invalidationArray(inv),
                "parameter", tags("rate", "revision"));
    }

    public synchronized Event selectDictionary(String caseId, String branchId, String channelId,
                                               String dictVersion, Integer baseRevision) {
        CasePack pack = packFor(caseId);
        if (!pack.dictionaries().containsKey(dictVersion)) {
            throw new IllegalArgumentException("unknown dictionary version " + dictVersion);
        }
        FoldView before = view(caseId, branchId);
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("channelId", channelId);
        payload.put("dictVersion", dictVersion);
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("dictionary", dictVersion, "BNR/BCD/discrete definitions"));
        List<String> affected = new ArrayList<>();
        before.engineering().forEach(n -> {
            if (n.get("channelId").asText().equals(channelId)) {
                affected.add(n.get("id").asText());
            }
        });
        before.aggregates().forEach(n -> {
            if (n.get("channelId").asText().equals(channelId)) {
                affected.add(n.get("id").asText());
            }
        });
        Invalidation inv = new Invalidation(
                "BNR/BCD/discrete interpretation is decided by dictionary version",
                "dictionary:" + dictVersion, affected,
                List.of("rawWord", "label", "dictionaryVersion", "engineering", "aggregate"),
                "channel:" + channelId);
        return store.append(caseId, branchId, before.lastEventId(),
                "DICT_VERSION_SELECTED", payload, depends, invalidationArray(inv),
                "parameter", tags("dictionary", "revision"));
    }

    /** Apply a prepared one-word local source change; invalidation stays local. */
    public synchronized Event applyPatch(String caseId, String branchId, String patchId,
                                         Integer baseRevision) {
        CasePack pack = packFor(caseId);
        SourcePatch patch = pack.patches().stream().filter(p -> p.patchId().equals(patchId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown patch " + patchId));
        FoldView before = view(caseId, branchId);
        JsonNode wordRow = null;
        for (JsonNode n : before.words()) {
            if (n.get("id").asText().equals(patch.wordId())) {
                wordRow = n;
            }
        }
        if (wordRow == null) {
            throw new IllegalArgumentException("patch target not in active source: " + patch.wordId());
        }
        int rev = store.wordRevision(caseId, patch.wordId()) + 1;
        RawWord current = store.rawWords(caseId).stream()
                .filter(w -> w.wordId().equals(patch.wordId()))
                .reduce((a, b) -> b).orElseThrow();
        String newHex = patch.wireHex() != null ? patch.wireHex() : current.wireHex();
        long newNs = patch.receivedAtNs() != null ? patch.receivedAtNs()
                : current.receivedAtNs() + patch.shiftMs() * 1_000_000L;
        String newIso = patch.receivedAtIso() != null ? patch.receivedAtIso()
                : java.time.Instant.parse(current.receivedAtIso()).plusMillis(patch.shiftMs()).toString();
        String fragmentId = patch.patchId();
        RawWord patched = store.patchWord(caseId, current, newHex, newNs, newIso, rev, fragmentId);

        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("patchId", patchId);
        payload.put("wordId", patched.wordId());
        payload.put("wireHex", newHex);
        payload.put("receivedAtNs", newNs);
        payload.put("receivedAtIso", newIso);
        payload.put("fragmentId", fragmentId);
        payload.put("revision", rev);
        payload.put("changes", patch.changes());
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("rawWord", patched.wordId() + ":r" + (rev - 1), "previous source revision"));
        List<String> affected = new ArrayList<>();
        affected.add(patched.wordId() + ":eng@" + wordRow.get("dictVersion").asText());
        affected.add(patched.wordId());
        String label = wordRow.get("labelOctal").asText();
        String keyId = ViewProjector.rateKeyId(caseId, patched.channelId(), label);
        before.rates().get("keys").forEach(n -> {
            if (n.get("id").asText().equals(keyId)) {
                affected.add(n.get("id").asText());
                n.get("intervals").forEach(i -> affected.add(i.get("id").asText()));
                n.get("candidates").forEach(c -> affected.add(c.get("id").asText()));
            }
        });
        before.aggregates().forEach(n -> {
            if (n.get("channelId").asText().equals(patched.channelId())
                    && n.get("labelOctal").asText().equals(label)) {
                affected.add(n.get("id").asText());
            }
        });
        Invalidation inv = new Invalidation(
                "one direct source changed; only entities depending on that word are recomputed",
                "rawWord:" + patched.wordId() + ":r" + rev, affected,
                List.of("rawWord", "engineering", "rateKey:" + keyId, "aggregate"),
                patch.expectedScope());
        return store.append(caseId, branchId, before.lastEventId(),
                "SOURCE_PATCHED", payload, depends, invalidationArray(inv),
                "source", tags("patch", "local-change"));
    }

    public synchronized Event adjudicate(String caseId, String branchId, String subjectId,
                                         String candidateId, boolean accepted, String rationale,
                                         Integer baseRevision) {
        FoldView before = view(caseId, branchId);
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.put("candidateId", candidateId);
        payload.put("selectedCandidateId", candidateId);
        payload.put("accepted", accepted);
        payload.put("rationale", rationale == null ? "" : rationale);
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("rateCandidate", candidateId, "candidate selected for adjudication"));
        depends.add(dep("rateParams", "gapFactor=" + before.gapFactor() + ",switchRatio="
                + before.switchRatio(), "parameters at adjudication"));
        ArrayNode invalidates = Json.MAPPER.createArrayNode();
        return store.append(caseId, branchId, before.lastEventId(),
                "CANDIDATE_ADJUDICATED", payload, depends, invalidates,
                "decision", tags(accepted ? "accept" : "reject"));
    }

    public synchronized Event revokeDecision(String caseId, String branchId, String subjectId,
                                             Integer baseRevision) {
        FoldView before = view(caseId, branchId);
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.putNull("selectedCandidateId");
        payload.put("rationale", "decision revoked; branch restored to prior candidate set");
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("decision", subjectId, "adjudication being revoked"));
        Invalidation inv = new Invalidation(
                "revoking restores the previous branch; candidates become proposed again",
                "decision:" + subjectId, List.of(subjectId),
                List.of("decision", "candidateStatus"), "subject-only");
        return store.append(caseId, branchId, before.lastEventId(),
                "DECISION_REVOKED", payload, depends, invalidationArray(inv),
                "decision", tags("revoke"));
    }

    public synchronized BranchInfo forkBranch(String caseId, String newBranchId,
                                              String name, int atSeq) {
        store.createBranch(caseId, newBranchId, "main", name, atSeq);
        List<Event> source = store.events(caseId, "main");
        Event last = null;
        for (Event e : source) {
            if (e.seq() > atSeq) {
                break;
            }
            store.appendWithIdentity(caseId, newBranchId,
                    remapParent(e.parentEventId(), newBranchId), e.type(), e.payload(),
                    e.depends(), e.invalidates(), e.kind(), e.tags(),
                    remapId(e.eventId(), newBranchId), e.seq(), e.revision());
            last = e;
        }
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("newBranchId", newBranchId);
        payload.put("forkedAtSeq", atSeq);
        payload.put("fromBranch", "main");
        ArrayNode deps = Json.MAPPER.createArrayNode();
        deps.add(dep("branch", "main@" + atSeq, "fork point"));
        store.appendWithIdentity(caseId, newBranchId,
                last == null ? null : remapId(last.eventId(), newBranchId),
                "BRANCH_FORKED", payload, deps, Json.MAPPER.createArrayNode(),
                "meta", tags("fork"), caseId + ":" + newBranchId + ":e" + (atSeq + 1),
                atSeq + 1, (last == null ? 0 : last.revision()) + 1);
        return new BranchInfo(newBranchId, "main", name, atSeq);
    }

    private String remapParent(String parentEventId, String branchId) {
        if (parentEventId == null) {
            return null;
        }
        return remapId(parentEventId, branchId);
    }

    public synchronized Event resolveConflict(String caseId, String branchId,
                                              JsonNode clientPayload, Integer baseRevision) {
        FoldView before = view(caseId, branchId);
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.set("clientPayload", clientPayload);
        payload.put("resolution", "second submitter rebases on head and re-applies explicitly");
        ArrayNode depends = Json.MAPPER.createArrayNode();
        depends.add(dep("branchRevision", String.valueOf(before.seq()), "head at resolution"));
        return store.append(caseId, branchId, before.lastEventId(),
                "CONFLICT_RESOLVED", payload, depends, Json.MAPPER.createArrayNode(),
                "decision", tags("conflict"));
    }

    public List<BranchInfo> branches(String caseId) {
        List<BranchInfo> out = new ArrayList<>();
        for (String branchId : store.branchIds(caseId)) {
            int forkAt = -1;
            String parent = null;
            for (Event e : store.events(caseId, branchId)) {
                if (e.type().equals("BRANCH_FORKED")) {
                    forkAt = e.payload().path("forkedAtSeq").asInt(-1);
                    parent = e.payload().path("fromBranch").asText("main");
                }
            }
            out.add(new BranchInfo(branchId, parent, branchId, forkAt));
        }
        return out;
    }

    public synchronized JsonNode exportCase(String caseId) {
        CasePack pack = packFor(caseId);
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("format", "wordline429-archive/1");
        root.set("case", store.caseMeta(caseId));
        ArrayNode branches = root.putArray("branches");
        for (String branchId : storeBranchIds(caseId)) {
            ObjectNode b = branches.addObject();
            b.put("branchId", branchId);
            ArrayNode evs = b.putArray("events");
            for (Event e : store.events(caseId, branchId)) {
                evs.add(Json.MAPPER.valueToTree(eventToJson(e)));
            }
        }
        ArrayNode words = root.putArray("rawWords");
        for (RawWord w : store.rawWords(caseId)) {
            ObjectNode wn = words.addObject();
            wn.put("wordId", w.wordId());
            wn.put("channelId", w.channelId());
            wn.put("position", w.position());
            wn.put("receivedAtNs", w.receivedAtNs());
            wn.put("receivedAtIso", w.receivedAtIso());
            wn.put("wireHex", w.wireHex());
            wn.put("fragmentId", w.fragmentId());
        }
        root.put("packId", pack.packId());
        return root;
    }

    /**
     * Import a previously exported archive into a fresh database. Identities,
     * event order and provenance references are preserved byte-for-byte;
     * failure rolls the entire import back.
     */
    public synchronized void importArchive(JsonNode archive) {
        String caseId = archive.get("case").get("caseId").asText();
        store.createCaseIfAbsent(caseId, archive.get("case").get("title").asText(),
                archive.get("case").path("packPath").asText(null));
        try {
            for (JsonNode w : archive.get("rawWords")) {
                RawWord rw = new RawWord(w.get("wordId").asText(), w.get("channelId").asText(),
                        w.get("position").asInt(), w.get("receivedAtNs").asLong(),
                        w.get("receivedAtIso").asText(), w.get("wireHex").asText(),
                        w.path("fragmentId").asText("imported"));
                store.insertFragment(caseId, rw.fragmentId(), List.of(rw));
            }
            for (JsonNode b : archive.get("branches")) {
                String branchId = b.get("branchId").asText();
                store.createBranch(caseId, branchId, branchId.equals("main") ? null : "main",
                        branchId, -1);
                for (JsonNode e : b.get("events")) {
                    Event ev = store.append(caseId, branchId,
                            e.path("parentEventId").asText(null),
                            e.get("type").asText(),
                            e.get("payload"), e.get("depends"), e.get("invalidates"),
                            e.path("kind").asText("source"),
                            e.has("tags") ? e.get("tags") : Json.MAPPER.createArrayNode(),
                            null);
                    if (!ev.eventId().equals(e.get("eventId").asText())) {
                        throw new IllegalStateException("identity drift: " + ev.eventId()
                                + " != " + e.get("eventId").asText());
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("archive import rolled back: " + e.getMessage(), e);
        }
    }

    public List<Event> branchEvents(String caseId, String branchId) {
        return store.events(caseId, branchId);
    }

    public List<String> storeBranchIds(String caseId) {
        return store.branchIds(caseId);
    }

    private Map<String, Object> eventToJson(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.eventId());
        m.put("seq", e.seq());
        m.put("parentEventId", e.parentEventId());
        m.put("type", e.type());
        m.put("payload", e.payload());
        m.put("depends", e.depends());
        m.put("invalidates", e.invalidates());
        m.put("kind", e.kind());
        m.put("tags", e.tags());
        return m;
    }

    private String headEventId(String caseId, String branchId) {
        List<Event> events = store.events(caseId, branchId);
        return events.isEmpty() ? null : events.get(events.size() - 1).eventId();
    }

    private String remapId(String eventId, String branchId) {
        return eventId == null ? null : eventId.replace(":main:", ":" + branchId + ":");
    }

    private ObjectNode dep(String kind, String ref, String role) {
        ObjectNode d = Json.MAPPER.createObjectNode();
        d.put("kind", kind);
        d.put("ref", ref);
        d.put("role", role);
        return d;
    }

    private ArrayNode invalidationArray(Invalidation inv) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        ObjectNode n = arr.addObject();
        n.put("rule", inv.rule());
        n.put("changedSource", inv.changedSource());
        n.put("scope", inv.scope());
        ArrayNode aff = n.putArray("affectedEntities");
        inv.affectedEntities().forEach(aff::add);
        ArrayNode path = n.putArray("dependencyPath");
        inv.dependencyPath().forEach(path::add);
        return arr;
    }

    private ArrayNode tags(String... tags) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (String t : tags) {
            arr.add(t);
        }
        return arr;
    }

    private List<String> rateEntityIds(FoldView view) {
        List<String> out = new ArrayList<>();
        view.rates().get("keys").forEach(n -> {
            out.add(n.get("id").asText());
            n.get("intervals").forEach(i -> out.add(i.get("id").asText()));
            n.get("candidates").forEach(c -> out.add(c.get("id").asText()));
        });
        return out;
    }

    public EventStore store() {
        return store;
    }
}
