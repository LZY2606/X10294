package a429.event;

import a429.casepkg.CasePackage;
import a429.casepkg.CasePackageLoader;
import a429.casepkg.DictMapper;
import a429.casepkg.TimeStamp;
import a429.db.Json;
import a429.db.Store;
import a429.domain.ChannelConfig;
import a429.domain.DictVersion;
import a429.domain.RateEngine;
import a429.domain.RawWord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds {@link BranchState} by replaying the append-only event log.
 *
 * Historical answers are event snapshots, never overwritten by newer
 * computation: when a revision invalidates a dependency, the old analysis is
 * marked stale with an explicit {@link Invalidation} and a fresh analysis
 * appears beside it, both still readable.
 */
public class Projector {

    private final Store store;
    private final CasePackageLoader loader = new CasePackageLoader();
    private final DerivationEngine derivation = new DerivationEngine();

    public Projector(Store store) {
        this.store = store;
    }

    public List<String> caseIds() {
        return store.caseIds();
    }

    public String caseTitle(String caseId) {
        return store.caseTitle(caseId);
    }

    public List<Store.BranchRow> branches(String caseId) {
        return store.branches(caseId);
    }

    /** Full current state of one branch. */
    public BranchState state(String branchId) {
        return replay(branchId, Long.MAX_VALUE);
    }

    /** State at one historical event (time travel: conclusions come from the log). */
    public BranchState stateAtEvent(String eventId) {
        Event ev = store.eventById(eventId);
        if (ev == null) {
            throw new IllegalArgumentException("unknown event: " + eventId);
        }
        return replay(ev.branchId(), ev.seq());
    }

    public Store store() {
        return store;
    }

    private BranchState replay(String branchId, long toSeq) {
        List<Event> events = store.eventsOnBranch(branchId);
        BranchState state = new BranchState();
        state.branchId = branchId;
        for (Event ev : events) {
            if (ev.seq() > toSeq) {
                break;
            }
            apply(ev, state);
            state.headSeq = ev.seq();
        }
        return state;
    }

    private void apply(Event ev, BranchState state) {
        JsonNode p = ev.payload();
        switch (ev.type()) {
            case EventType.CASE_CREATED -> {
                state.caseId = ev.caseId();
                state.title = p.path("title").asText();
                state.rulesVersion = p.path("rulesVersion").asText("rules-1.0.0");
                if (p.has("package")) {
                    state.pkg = loader.loadFromResources();
                }
            }
            case EventType.SOURCE_BATCH_IMPORTED -> applySourceBatch(ev, state);
            case EventType.DICTIONARY_REGISTERED -> applyDictRegistered(ev, state);
            case EventType.DICT_ACTIVATED -> state.activeDictRef = p.path("dictRef").asText();
            case EventType.PARAMETERS_REVISED -> {
                state.rateParams = new RateEngine.Params(
                        p.path("gapThresholdMs").asDouble(state.rateParams.gapThresholdMs()),
                        p.path("toleranceFraction").asDouble(state.rateParams.toleranceFraction()),
                        RateEngine.STANDARD_SPEEDS);
                if (p.has("labelConvention")) {
                    state.labelConvention = p.path("labelConvention").asText();
                }
            }
            case EventType.ANALYSIS_OBSERVED -> applyAnalysis(ev, state);
            case EventType.RULING_MADE -> applyRuling(ev, state);
            case EventType.RULING_REVOKED -> {
                String rulingId = p.path("rulingId").asText();
                state.rulings.stream().filter(r -> r.rulingId().equals(rulingId)).findFirst()
                        .ifPresent(r -> {
                            int idx = state.rulings.indexOf(r);
                            state.rulings.set(idx, withRevoked(r, ev.eventId()));
                        });
            }
            case EventType.DEPENDENCIES_INVALIDATED -> applyInvalidation(ev, state);
            case EventType.COUNTEREXAMPLE_REPLAYED -> state.counterexamples.add(
                    CounterexampleMapper.fromEvent(ev));
            case EventType.BRANCH_FORKED -> {
            }
            case EventType.VIEW_PINNED -> {
            }
            default -> {
            }
        }
    }

    private void applySourceBatch(Event ev, BranchState state) {
        JsonNode p = ev.payload();
        for (JsonNode ch : p.path("channels")) {
            Map<String, Object> notes = new LinkedHashMap<>();
            ch.path("notes").fields().forEachRemaining(f -> notes.put(f.getKey(),
                    Json.MAPPER.convertValue(f.getValue(), Object.class)));
            state.channels.put(ch.path("id").asText(), new ChannelConfig(
                    ch.path("id").asText(), ch.path("name").asText(),
                    ch.path("configuredSpeed").asText(), notes));
        }
        for (JsonNode w : p.path("words")) {
            String id = w.path("wordId").asText();
            String rawText = w.path("rawHex").asText();
            long raw;
            try {
                raw = Long.parseLong(rawText.replace("0x", "").replace("0X", ""), 16);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("imported raw word not 32-bit hex: " + rawText);
            }
            if ((raw & 0xFFFFFFFFL) != raw || raw > 0xFFFFFFFFL) {
                throw new IllegalStateException("raw word exceeds 32 bits: " + rawText);
            }
            String receiveText = w.path("receiveText").asText();
            RawWord word = new RawWord(id, w.path("seq").asLong(), receiveText,
                    TimeStamp.parseEpochNanos(receiveText), w.path("channelId").asText(),
                    raw, rawText);
            state.words.put(id, word);
        }
    }

    private void applyDictRegistered(Event ev, BranchState state) {
        JsonNode p = ev.payload();
        CasePackage.DictionarySpec spec = new CasePackage.DictionarySpec(
                p.path("dictId").asText(), p.path("version").asText(), false,
                p.path("path").asText(), Map.of());
        DictVersion dict;
        if (p.has("inline") && p.path("inline").isObject()) {
            try {
                byte[] body = Json.MAPPER.writeValueAsBytes(p.path("inline"));
                dict = DictMapper.parse(spec, new java.io.ByteArrayInputStream(body));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } else {
            dict = loader.loadDictionary(spec);
        }
        state.dictionaries.put(dict.dictId() + "@" + dict.version(), dict);
    }

    private void applyAnalysis(Event ev, BranchState state) {
        JsonNode p = ev.payload();
        List<String> candidates = new ArrayList<>();
        p.path("candidates").forEach(c -> candidates.add(c.asText()));
        List<String> deps = new ArrayList<>();
        p.path("dependencyKeys").forEach(d -> deps.add(d.asText()));
        state.analyses.add(new Analysis(p.path("analysisId").asText(), ev.eventId(), ev.seq(),
                p.path("kind").asText(), p.path("targetId").asText(), List.copyOf(candidates),
                p.path("unique").asBoolean(), List.copyOf(deps),
                p.path("basisSummary").asText(), false, null));
    }

    private void applyRuling(Event ev, BranchState state) {
        JsonNode p = ev.payload();
        List<String> retained = new ArrayList<>();
        p.path("retainedCandidates").forEach(c -> retained.add(c.asText()));
        List<String> rejected = new ArrayList<>();
        p.path("rejectedCandidates").forEach(c -> rejected.add(c.asText()));
        List<String> deps = new ArrayList<>();
        p.path("dependencyKeys").forEach(d -> deps.add(d.asText()));
        state.rulings.add(new Ruling(p.path("rulingId").asText(), ev.eventId(), ev.seq(),
                p.path("scope").asText(), p.path("targetId").asText(),
                p.path("decision").asText(), p.path("chosenCandidate").asText(""),
                List.copyOf(retained), List.copyOf(rejected), p.path("note").asText(""),
                p.path("basedOnAnalysisId").asText(""), List.copyOf(deps), false, null, null));
    }

    private Ruling withRevoked(Ruling r, String eventId) {
        return new Ruling(r.rulingId(), r.eventId(), r.seq(), r.scope(), r.targetId(),
                r.decision(), r.chosenCandidate(), r.retainedCandidates(), r.rejectedCandidates(),
                r.note(), r.basedOnAnalysisId(), r.dependencyKeys(), r.stale(), r.staleReason(),
                eventId);
    }

    private void applyInvalidation(Event ev, BranchState state) {
        JsonNode p = ev.payload();
        List<String> keys = new ArrayList<>();
        p.path("dependencyKeys").forEach(k -> keys.add(k.asText()));
        List<String> ana = new ArrayList<>();
        p.path("invalidatedAnalysisIds").forEach(a -> ana.add(a.asText()));
        List<String> rul = new ArrayList<>();
        p.path("invalidatedRulingIds").forEach(a -> rul.add(a.asText()));
        Invalidation inv = new Invalidation(p.path("reason").asText(), ev.eventId(),
                List.copyOf(keys), List.copyOf(ana), List.copyOf(rul), p.path("scope").asText());
        state.invalidations.add(inv);
        String reason = "stale: " + p.path("reason").asText() + " (" + ev.eventId() + ")";
        for (int i = 0; i < state.analyses.size(); i++) {
            Analysis a = state.analyses.get(i);
            if (ana.contains(a.analysisId())) {
                state.analyses.set(i, new Analysis(a.analysisId(), a.eventId(), a.seq(), a.kind(),
                        a.targetId(), a.candidates(), a.unique(), a.dependencyKeys(),
                        a.basisSummary(), true, reason));
            }
        }
        for (int i = 0; i < state.rulings.size(); i++) {
            Ruling r = state.rulings.get(i);
            if (rul.contains(r.rulingId())) {
                state.rulings.set(i, new Ruling(r.rulingId(), r.eventId(), r.seq(), r.scope(),
                        r.targetId(), r.decision(), r.chosenCandidate(), r.retainedCandidates(),
                        r.rejectedCandidates(), r.note(), r.basedOnAnalysisId(), r.dependencyKeys(),
                        true, reason, r.revokedByEventId()));
            }
        }
    }

    /** Recompute derived views from originals (not stored; always tied to active refs). */
    public DerivationEngine.Derived derive(BranchState state) {
        return derivation.derive(state);
    }

    public CasePackageLoader loader() {
        return loader;
    }
}
