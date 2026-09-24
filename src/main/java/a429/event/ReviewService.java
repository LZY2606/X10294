package a429.event;

import a429.casepkg.CasePackage;
import a429.casepkg.CasePackageLoader;
import a429.casepkg.MutationEngine;
import a429.db.Json;
import a429.db.Store;
import a429.domain.Aggregator;
import a429.domain.Bits;
import a429.domain.ChannelConfig;
import a429.domain.Decoded;
import a429.domain.DecodedWord;
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
 * Application workflow for the avionics data group:
 * initial import, parameter revision, local source change, rulings, forks,
 * invalidation scopes and counterexample replay.
 */
public class ReviewService {

    private final Store store;
    private final Projector projector;
    private final MutationEngine mutationEngine = new MutationEngine();

    public ReviewService(Store store, Projector projector) {
        this.store = store;
        this.projector = projector;
    }

    public Projector projector() {
        return projector;
    }

    public CasePackage shippedPackage() {
        return new CasePackageLoader().loadFromResources();
    }

    // ------------------------------------------------------------------
    // Initial import: the whole narrative arrives as one atomic batch.
    // ------------------------------------------------------------------

    public List<Event> importShippedCase(String actor) {
        CasePackageLoader loader = new CasePackageLoader();
        CasePackage pkg = loader.loadFromResources();
        if (store.caseIds().contains(pkg.id())) {
            throw new IllegalArgumentException("case already imported: " + pkg.id());
        }
        List<Store.NewEvent> batch = new ArrayList<>();
        long now = System.currentTimeMillis();

        ObjectNode created = Json.obj();
        created.put("title", pkg.title());
        created.put("rulesVersion", pkg.rulesVersion());
        created.put("basis", pkg.basis());
        created.putObject("package").put("id", pkg.id());
        batch.add(new Store.NewEvent(pkg.id() + "-case", EventType.CASE_CREATED, now, created,
                ruleBasis("shipped offline case package: " + pkg.title())));

        // Channels + raw words in one SourceBatchImported event (atomic batch).
        ObjectNode batchPayload = Json.obj();
        ArrayNode channels = batchPayload.putArray("channels");
        for (CasePackage.ChannelSpec ch : pkg.channels()) {
            ObjectNode co = channels.addObject();
            co.put("id", ch.id());
            co.put("name", ch.name());
            co.put("configuredSpeed", ch.configuredSpeed());
            ObjectNode notes = co.putObject("notes");
            ch.notes().forEach(notes::putPOJO);
        }
        ArrayNode words = batchPayload.putArray("words");
        for (CasePackage.WordSpec w : pkg.words()) {
            ObjectNode wo = words.addObject();
            wo.put("wordId", w.id());
            wo.put("seq", w.seq());
            wo.put("receiveText", w.receiveText());
            wo.put("channelId", w.channelId());
            wo.put("rawHex", w.rawHex());
            wo.put("origin", w.origin());
        }
        batchPayload.put("wordCount", pkg.words().size());
        batchPayload.put("note", "original representation retained; no normalisation at import");
        batch.add(new Store.NewEvent(pkg.id() + "-import", EventType.SOURCE_BATCH_IMPORTED,
                now + 1, batchPayload, basis(
                        "in-place sequence, receive time, channel, raw 32 bits imported verbatim",
                        List.of())));

        for (CasePackage.DictionarySpec d : pkg.dictionaries()) {
            DictVersion dict = loader.loadDictionary(d);
            ObjectNode dp = Json.obj();
            dp.put("dictId", d.id());
            dp.put("version", d.version());
            dp.put("path", d.path());
            dp.putPOJO("changeNote", d.changeNote());
            batch.add(new Store.NewEvent(pkg.id() + "-dict-" + d.version(),
                    EventType.DICTIONARY_REGISTERED, now + 2, dp, basis(
                    "dictionary revision registered; interpretation kind and layout owned by dict",
                    List.of())));
        }
        // initial parameters
        ObjectNode params = Json.obj();
        params.put("gapThresholdMs", pkg.initialParams().gapThresholdMs());
        params.put("toleranceFraction", pkg.initialParams().toleranceFraction());
        params.put("labelConvention", pkg.initialParams().labelConvention());
        batch.add(new Store.NewEvent(pkg.id() + "-params-v1", EventType.PARAMETERS_REVISED,
                now + 3, params, ruleBasis("initial rate parameters and label presentation")));

        CasePackage.DictionarySpec active = pkg.dictionaries().stream()
                .filter(CasePackage.DictionarySpec::activate).findFirst()
                .orElse(pkg.dictionaries().get(0));
        ObjectNode act = Json.obj();
        act.put("dictRef", active.id() + "@" + active.version());
        batch.add(new Store.NewEvent(pkg.id() + "-dict-activate-v1", EventType.DICT_ACTIVATED,
                now + 4, act, ruleBasis("active dictionary selected")));

        store.createCase(pkg.id(), pkg.title(), now);
        store.ensureBranch("main", pkg.id(), null, null, now, "main review line");
        return store.appendBatch(pkg.id(), "main", -1, actor, batch);
    }

    /** After import, materialise the baseline analysis snapshot (one atomic batch). */
    public List<Event> recordBaselineAnalyses(String caseId, String branchId, String actor) {
        BranchState state = projector.state(branchId);
        DerivationEngine.Derived derived = projector.derive(state);
        List<Store.NewEvent> batch = new ArrayList<>();
        long now = System.currentTimeMillis();
        int n = 0;
        for (DerivationEngine.AnalysisSeed seed : derived.seeds()) {
            ObjectNode p = Json.obj();
            p.put("analysisId", seed.analysisId());
            p.put("kind", seed.kind());
            p.put("targetId", seed.targetId());
            ArrayNode cands = p.putArray("candidates");
            seed.candidates().forEach(cands::add);
            p.put("unique", seed.unique());
            ArrayNode deps = p.putArray("dependencyKeys");
            seed.dependencyKeys().forEach(deps::add);
            p.put("basisSummary", seed.basisSummary());
            p.set("detail", seed.detail());
            batch.add(new Store.NewEvent(branchId + "-ana-" + seed.analysisId(),
                    EventType.ANALYSIS_OBSERVED, now + n, p, basis(
                    "derived from preserved originals with " + state.rulesVersion
                            + " and " + state.activeDictRef, seed.dependencyKeys())));
            n++;
        }
        return store.appendBatch(caseId, branchId, projector.state(branchId).headSeq, actor, batch);
    }

    // ------------------------------------------------------------------
    // Parameter revision: invalidate only the affected dependency scope.
    // ------------------------------------------------------------------

    public List<Event> reviseParameters(String caseId, String branchId, long baseSeq,
                                        double gapThresholdMs, double toleranceFraction,
                                        String labelConvention, String actor, String note) {
        BranchState before = projector.state(branchId);
        List<String> keys = new ArrayList<>();
        if (gapThresholdMs != before.rateParams.gapThresholdMs()) {
            keys.add("params:gapThreshold=" + before.rateParams.gapThresholdMs());
        }
        if (toleranceFraction != before.rateParams.toleranceFraction()) {
            keys.add("params:tolerance=" + before.rateParams.toleranceFraction());
        }
        String changedConvention = labelConvention == null ? before.labelConvention
                : labelConvention;

        List<Store.NewEvent> batch = new ArrayList<>();
        long now = System.currentTimeMillis();
        ObjectNode params = Json.obj();
        params.put("gapThresholdMs", gapThresholdMs);
        params.put("toleranceFraction", toleranceFraction);
        params.put("labelConvention", changedConvention);
        params.put("note", note);
        batch.add(new Store.NewEvent(branchId + "-params-" + (before.headSeq + 1),
                EventType.PARAMETERS_REVISED, now, params, basis(
                "parameter revision; presentation vs decode kept distinct",
                List.copyOf(keys))));

        List<Event> committed = store.appendBatch(caseId, branchId, baseSeq, actor, batch);

        if (!keys.isEmpty()) {
            invalidateByDependencyKeys(caseId, branchId, actor, keys,
                    "parameters revised: " + note, committed.get(0).eventId());
            recordDerivedAnalyses(caseId, branchId, actor,
                    store.eventsOnBranch(branchId).size() == 0 ? -1 : projector.state(branchId).headSeq);
        }
        return committed;
    }

    public List<Event> activateDictionary(String caseId, String branchId, long baseSeq,
                                          String dictRef, String actor, String note) {
        BranchState before = projector.state(branchId);
        if (before.dictionaries.get(dictRef) == null) {
            throw new IllegalArgumentException("dictionary not registered: " + dictRef);
        }
        ObjectNode act = Json.obj();
        act.put("dictRef", dictRef);
        act.put("previousRef", before.activeDictRef);
        act.put("note", note);
        List<Store.NewEvent> batch = List.of(new Store.NewEvent(
                branchId + "-dict-activate-" + dictRef.replace('@', '-'),
                EventType.DICT_ACTIVATED, System.currentTimeMillis(), act, basis(
                "BNR/BCD/discrete interpretation follows dictionary version; bits unchanged",
                List.of("dict:" + before.activeDictRef))));
        List<Event> committed = store.appendBatch(caseId, branchId, baseSeq, actor, batch);
        invalidateByDependencyKeys(caseId, branchId, actor,
                List.of("dict:" + before.activeDictRef),
                "dictionary activation " + before.activeDictRef + " -> " + dictRef,
                committed.get(0).eventId());
        recordDerivedAnalyses(caseId, branchId, actor, projector.state(branchId).headSeq);
        return committed;
    }

    private void invalidateByDependencyKeys(String caseId, String branchId, String actor,
                                            List<String> changedKeys, String reason,
                                            String causedByEventId) {
        BranchState state = projector.state(branchId);
        List<String> invalidAnalyses = new ArrayList<>();
        List<String> invalidRulings = new ArrayList<>();
        for (Analysis a : state.analyses) {
            if (!a.stale() && a.dependencyKeys().stream().anyMatch(changedKeys::contains)) {
                invalidAnalyses.add(a.analysisId());
            }
        }
        for (Ruling r : state.rulings) {
            if (!r.stale() && r.revokedByEventId() == null
                    && r.dependencyKeys().stream().anyMatch(changedKeys::contains)) {
                invalidRulings.add(r.rulingId());
            }
        }
        if (invalidAnalyses.isEmpty() && invalidRulings.isEmpty()) {
            return;
        }
        ObjectNode p = Json.obj();
        p.put("reason", reason);
        p.put("causedByEventId", causedByEventId);
        p.put("scope", invalidRulings.isEmpty() && invalidAnalyses.size() <= 1
                ? "LOCAL" : "DEPENDENCY_SUBSET");
        ArrayNode keys = p.putArray("dependencyKeys");
        changedKeys.forEach(keys::add);
        ArrayNode ana = p.putArray("invalidatedAnalysisIds");
        invalidAnalyses.forEach(ana::add);
        ArrayNode rul = p.putArray("invalidatedRulingIds");
        invalidRulings.forEach(rul::add);
        store.appendBatch(caseId, branchId, state.headSeq, actor, List.of(
                new Store.NewEvent(branchId + "-inv-" + causedByEventId,
                        EventType.DEPENDENCIES_INVALIDATED, System.currentTimeMillis(), p,
                        basis("blast radius computed from dependency edges", changedKeys))));
    }

    private List<Event> recordDerivedAnalyses(String caseId, String branchId, String actor,
                                              long baseSeq) {
        BranchState state = projector.state(branchId);
        DerivationEngine.Derived derived = projector.derive(state);
        List<Store.NewEvent> batch = new ArrayList<>();
        long now = System.currentTimeMillis();
        int n = 0;
        for (DerivationEngine.AnalysisSeed seed : derived.seeds()) {
            ObjectNode p = Json.obj();
            p.put("analysisId", seed.analysisId());
            p.put("kind", seed.kind());
            p.put("targetId", seed.targetId());
            ArrayNode cands = p.putArray("candidates");
            seed.candidates().forEach(cands::add);
            p.put("unique", seed.unique());
            ArrayNode deps = p.putArray("dependencyKeys");
            seed.dependencyKeys().forEach(deps::add);
            p.put("basisSummary", seed.basisSummary());
            p.set("detail", seed.detail());
            p.put("supersedesStale", true);
            batch.add(new Store.NewEvent(branchId + "-ana2-" + seed.analysisId() + "-"
                    + (state.headSeq + n + 1), EventType.ANALYSIS_OBSERVED, now + n, p,
                    basis("fresh derivation after revision; stale snapshots retained",
                            seed.dependencyKeys())));
            n++;
        }
        return store.appendBatch(caseId, branchId, baseSeq, actor, batch);
    }

    // ------------------------------------------------------------------
    // Human rulings: accept/reject/adjust. Candidates are kept; undo restores.
    // ------------------------------------------------------------------

    public List<Event> makeRuling(String caseId, String branchId, long baseSeq, String scope,
                                  String targetId, String decision, String chosenCandidate,
                                  List<String> allCandidates, String basedOnAnalysisId,
                                  String actor, String note) {
        BranchState state = projector.state(branchId);
        Analysis based = state.analyses.stream()
                .filter(a -> a.analysisId().equals(basedOnAnalysisId)).findFirst().orElse(null);
        List<String> deps = based == null ? List.of() : based.dependencyKeys();
        long seq = baseSeq + 1;
        String rulingId = "ruling-" + branchId + "-" + seq + "-" + targetId;
        ObjectNode p = Json.obj();
        p.put("rulingId", rulingId);
        p.put("scope", scope);
        p.put("targetId", targetId);
        p.put("decision", decision);
        p.put("chosenCandidate", chosenCandidate == null ? "" : chosenCandidate);
        ArrayNode retained = p.putArray("retainedCandidates");
        allCandidates.forEach(retained::add);
        ArrayNode rejected = p.putArray("rejectedCandidates");
        allCandidates.stream().filter(c -> !c.equals(chosenCandidate)).forEach(rejected::add);
        p.put("basedOnAnalysisId", basedOnAnalysisId);
        p.put("note", note);
        ArrayNode depArr = p.putArray("dependencyKeys");
        deps.forEach(depArr::add);
        Store.NewEvent ne = new Store.NewEvent(branchId + "-ruling-" + seq,
                EventType.RULING_MADE, System.currentTimeMillis(), p, basis(
                "reviewer " + decision + "; source data untouched, ruling reversible", deps));
        return store.appendBatch(caseId, branchId, baseSeq, actor, List.of(ne));
    }

    public List<Event> revokeRuling(String caseId, String branchId, long baseSeq,
                                    String rulingId, String actor, String note) {
        BranchState state = projector.state(branchId);
        Ruling ruling = state.rulings.stream().filter(r -> r.rulingId().equals(rulingId))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("unknown ruling: " + rulingId));
        if (ruling.revokedByEventId() != null) {
            throw new IllegalArgumentException("ruling already revoked: " + rulingId);
        }
        ObjectNode p = Json.obj();
        p.put("rulingId", rulingId);
        p.put("restoresDecision", "");
        p.put("note", "undone: previous branch state restored, candidates preserved; " + note);
        ArrayNode deps = p.putArray("dependencyKeys");
        ruling.dependencyKeys().forEach(deps::add);
        Store.NewEvent ne = new Store.NewEvent(branchId + "-revoke-" + (baseSeq + 1),
                EventType.RULING_REVOKED, System.currentTimeMillis(), p, basis(
                "undo restores prior branch without rewriting source or history",
                ruling.dependencyKeys()));
        return store.appendBatch(caseId, branchId, baseSeq, actor, List.of(ne));
    }

    /** Fork a re-interpretation branch; originals and old rulings stay readable. */
    public String forkBranch(String caseId, String parentBranchId, long baseSeq,
                             String newBranchId, String actor, String note, String fromEventId) {
        if (store.branches(caseId).stream().anyMatch(b -> b.branchId().equals(newBranchId))) {
            throw new IllegalArgumentException("branch exists: " + newBranchId);
        }
        long head = store.headSeq(parentBranchId);
        if (baseSeq != head) {
            throw new Store.ConflictException(baseSeq, head);
        }
        store.ensureBranch(newBranchId, caseId, parentBranchId, fromEventId,
                System.currentTimeMillis(), note);
        // Materialise parent history into the child with identical identities and seq.
        long cutoff = Long.MAX_VALUE;
        if (fromEventId != null) {
            Event e = store.eventById(fromEventId);
            if (e == null || !e.branchId().equals(parentBranchId)) {
                throw new IllegalArgumentException("fork point not on parent branch");
            }
            cutoff = e.seq();
        }
        for (Event ev : store.eventsOnBranch(parentBranchId)) {
            if (ev.seq() > cutoff) {
                continue;
            }
            store.insertInherited(new Event(ev.eventId(), caseId, newBranchId, ev.seq(),
                    ev.type(), ev.createdEpochMs(), ev.actor(), ev.payload(), ev.basis()));
        }
        store.setHeadSeqNoCommit(newBranchId, cutoff == Long.MAX_VALUE ? head : cutoff);
        ObjectNode p = Json.obj();
        p.put("newBranchId", newBranchId);
        p.put("parentBranchId", parentBranchId);
        p.put("forkedFromEventId", fromEventId == null ? "" : fromEventId);
        p.put("note", note);
        store.connection().commit();
        store.appendBatch(caseId, newBranchId, -1, actor, List.of(new Store.NewEvent(
                newBranchId + "-fork", EventType.BRANCH_FORKED,
                System.currentTimeMillis(), p, basis("re-interpretation fork; old branch, "
                        + "rulings and raw bytes remain readable", List.of()))));
        return newBranchId;
    }

    /** Late saver helper: fetch the other side's conflicting rulings for the diff UI. */
    public ConflictView conflictView(String branchId, long baseSeq, String targetId,
                                     Ruling attempted) {
        List<Ruling> committed = new ArrayList<>();
        for (Ruling r : projector.state(branchId).rulings) {
            if (r.targetId().equals(targetId) && r.revokedByEventId() == null
                    && r.seq() > baseSeq) {
                committed.add(r);
            }
        }
        return new ConflictView(targetId, baseSeq, store.headSeq(branchId), attempted, committed);
    }

    // ------------------------------------------------------------------
    // Counterexample archive: baseline preserved, one direct source changed
    // per mutation; adopted and rejected outcomes both stored.
    // ------------------------------------------------------------------

    public List<Event> replayCounterexamples(String caseId, String branchId, String actor) {
        CasePackage pkg = shippedPackage();
        BranchState state = projector.state(branchId);
        CasePackageLoader loader = new CasePackageLoader();

        Map<String, DictVersion> dictByRef = new LinkedHashMap<>();
        for (CasePackage.DictionarySpec spec : pkg.dictionaries()) {
            dictByRef.put(spec.id() + "@" + spec.version(), loader.loadDictionary(spec));
        }
        DictVersion activeDict = state.dictionaries.get(state.activeDictRef);
        a429.domain.Interpreter interpreter = new a429.domain.Interpreter();
        RateEngine rateEngine = new RateEngine();
        Aggregator aggregator = new Aggregator();

        BranchState baselineState = projector.state(branchId);
        DerivationEngine.Derived baselineDerived = projector.derive(baselineState);
        Map<String, DecodedWord> baselineDecode = new LinkedHashMap<>();
        for (DecodedWord row : baselineDerived.rows()) {
            baselineDecode.put(row.word().wordId(), row);
        }

        List<Store.NewEvent> batch = new ArrayList<>();
        long now = System.currentTimeMillis();
        int ord = 0;
        for (CasePackage.MutationSpec mutation : pkg.mutations()) {
            JsonNode op = mutationOp(mutation);
            MutationEngine.MutatedCase mutated = mutationEngine.apply(mutation, op,
                    baselineState.words);
            String convention = mutated.presentationOverride() != null
                    ? mutated.presentationOverride() : state.labelConvention;

            Map<String, DecodedWord> mutatedDecode = new LinkedHashMap<>();
            for (RawWord w : mutated.words().values()) {
                mutatedDecode.put(w.wordId(),
                        new DecodedWord(w, interpreter.decode(w, activeDict, convention)));
            }

            Map<String, List<RawWord>> byChannel = new LinkedHashMap<>();
            mutated.words().values().forEach(w ->
                    byChannel.computeIfAbsent(w.channelId(), k -> new ArrayList<>()).add(w));
            Map<String, RateEngine.ChannelResult> mutatedRates = new LinkedHashMap<>();
            byChannel.forEach((ch, ws) -> mutatedRates.put(ch,
                    rateEngine.analyse(ch, ws, state.rateParams, "mut-ch-" + ch)));

            ObjectNode payload = Json.obj();
            payload.put("mutationId", mutation.id());
            payload.put("title", mutation.title());
            payload.put("alters", mutation.alters());
            payload.put("directSourceWordId", mutation.wordId());
            payload.put("baselineRef", pkg.id() + "-import");
            payload.put("op", op);
            payload.set("inputFragment", mutated.inputFragment());

            ArrayNode outcomes = payload.putArray("outcomes");
            for (CasePackage.ExpectationSpec expectation : mutation.expectations()) {
                RawWord w = mutated.mutatedWord();
                Decoded d = mutatedDecode.get(w.wordId()).decoded();
                Decoded baseD = baselineDecode.get(w.wordId()).decoded();
                Object observedValue = evaluateExpectation(expectation.check(),
                        d, baseD, mutated, mutatedRates, w, op);
                boolean observed = Boolean.TRUE.equals(observedValue);
                ObjectNode o = outcomes.addObject();
                o.put("check", expectation.check());
                o.put("expectedAccepted", expectation.accepted());
                o.put("observed", observed);
                o.put("adopted", observed == expectation.accepted());
                o.put("expectedValue", expectation.expected() == null
                        ? expectation.accepted() : String.valueOf(expectation.expected()));
                o.put("observedValue", String.valueOf(observedValue));
                o.put("rationale", expectation.rationale());
            }

            // Blast radius: decode/rate objects of untouched words must remain identical.
            ArrayNode unchanged = payload.putArray("unaffectedObjects");
            for (CasePackage.ExpectedObjectSpec u : mutation.unaffectedObjects()) {
                ObjectNode uo = unchanged.addObject();
                uo.put("objectId", u.objectId());
                uo.put("aspect", u.aspect());
                uo.putPOJO("value", u.value());
                uo.put("verified", verifyUnaffected(u, mutatedDecode, baselineDecode,
                        mutatedRates, baselineDerived.rateResults()));
            }

            ObjectNode scope = Json.obj();
            scope.put("kind", "SINGLE_SOURCE");
            scope.put("changedObjectId", mutation.wordId());
            scope.put("changedAspect", mutation.alters());
            scope.put("verifiedOutOfScopeObjects", mutation.unaffectedObjects().size());
            payload.set("blastRadius", scope);

            ObjectNode basis = basis("single direct source altered; blast radius limited to "
                    + "the mutated word and rate intervals adjacent to it; bad parity never "
                    + "enters aggregates; octal presentation preserves bits", List.of(
                    "source:" + mutation.wordId(),
                    "dict:" + state.activeDictRef,
                    "rules:" + state.rulesVersion));
            batch.add(new Store.NewEvent(branchId + "-counter-" + mutation.id(),
                    EventType.COUNTEREXAMPLE_REPLAYED, now + ord, payload, basis));
            ord++;
        }
        return store.appendBatch(caseId, branchId, state.headSeq, actor, batch);
    }

    private JsonNode mutationOp(CasePackage.MutationSpec mutation) {
        try {
            String path = CasePackageLoader.PACKAGE_DIR + "/mutations/" + mutation.id() + ".json";
            try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("missing mutation op file: " + path);
                }
                return Json.MAPPER.readTree(in).path("op");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object evaluateExpectation(String check, Decoded d, Decoded baseD,
                                       MutationEngine.MutatedCase mutated,
                                       Map<String, RateEngine.ChannelResult> rates,
                                       RawWord word, JsonNode op) {
        return switch (check) {
            case "PARITY_FAIL" -> !d.parityOk();
            case "PARITY_OK" -> d.parityOk();
            case "PARITY_UNCHANGED" -> d.parityOk() == baseD.parityOk();
            case "LABEL_BITS_UNCHANGED" -> d.labelByte() == baseD.labelByte();
            case "CANONICAL_OCTAL_UNCHANGED" ->
                    d.labelCanonicalOctal().equals(baseD.labelCanonicalOctal());
            case "WIRE_OCTAL_PRESENTED" -> op.path("expectWireOctal").asText()
                    .equals(d.labelWireOctal());
            case "RAW_BITS_IDENTICAL" -> mutated.mutatedWord().raw()
                    == Long.parseLong(op.path("expectRawHex").asText().replace("0x", ""), 16);
            case "BNR_NEGATIVE_VALUE" -> d.engineering() != null && d.engineering().numeric() != null
                    && d.engineering().numeric() < 0;
            case "BCD_UNCONVERTED_DIAGNOSTIC" -> d.engineering() != null
                    && !d.engineering().converted()
                    && d.diagnostics().stream().anyMatch(x -> x.startsWith("BCD_ILLEGAL_DIGIT"));
            case "SSM_INVALID" -> d.engineering() != null && !d.engineering().validState();
            case "RATE_SWITCH_DETECTED" -> {
                RateEngine.ChannelResult r = rates.get(word.channelId());
                yield r.segments().stream().filter(s -> "TRAFFIC".equals(s.kind())).count() >= 2;
            }
            case "GAP_SEGMENT_PRESENT" -> {
                RateEngine.ChannelResult r = rates.get(word.channelId());
                yield r.segments().stream().anyMatch(s -> "GAP".equals(s.kind()));
            }
            case "MULTI_CANDIDATE_RETAINED" -> {
                RateEngine.ChannelResult r = rates.get(word.channelId());
                yield r.segments().stream().anyMatch(s -> s.candidateSpeedsBps().size() > 1);
            }
            default -> false;
        };
    }

    private boolean verifyUnaffected(CasePackage.ExpectedObjectSpec u,
                                     Map<String, DecodedWord> mutatedDecode,
                                     Map<String, DecodedWord> baselineDecode,
                                     Map<String, RateEngine.ChannelResult> mutatedRates,
                                     Map<String, RateEngine.ChannelResult> baselineRates) {
        String objectId = u.objectId();
        Object expected = u.value();
        if (objectId.startsWith("word:")) {
            String wordId = objectId.substring(5);
            Decoded a = baselineDecode.get(wordId).decoded();
            Decoded b = mutatedDecode.get(wordId).decoded();
            return switch (u.aspect()) {
                case "raw" -> b.labelByte() == a.labelByte()
                        && b.data() == a.data() && b.ssm() == a.ssm() && b.sdi() == a.sdi();
                case "canonicalOctal" -> b.labelCanonicalOctal().equals(String.valueOf(expected));
                case "engineeringText" -> a.engineering() != null && b.engineering() != null
                        && String.valueOf(expected).equals(
                        b.engineering().text() == null ? "" : b.engineering().text());
                default -> true;
            };
        }
        if (objectId.startsWith("segment:")) {
            String[] parts = objectId.substring(8).split("/", 2);
            RateEngine.ChannelResult base = baselineRates.get(parts[0]);
            RateEngine.ChannelResult mut = mutatedRates.get(parts[0]);
            if (base == null || mut == null) {
                return false;
            }
            var baseSeg = base.segments().stream().filter(s -> s.segmentId().equals(parts[1]))
                    .findFirst().orElse(null);
            var mutSeg = mut.segments().stream().filter(s -> s.segmentId().equals(parts[1]))
                    .findFirst().orElse(null);
            if (baseSeg == null || mutSeg == null) {
                return false;
            }
            return switch (u.aspect()) {
                case "candidateSpeedsBps" ->
                        mutSeg.candidateSpeedsBps().toString().equals(String.valueOf(expected)
                                .replace(" ", ", "));
                case "meanIntervalMs" ->
                        String.valueOf(mutSeg.meanIntervalMs()).equals(String.valueOf(expected));
                default -> true;
            };
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Local source change (one batch, rollback-safe) and machine-readable basis
    // ------------------------------------------------------------------

    /**
     * A local source-data change: replaces one raw word / receive text in a new
     * SourceBatchImported event and records its limited dependency blast radius.
     */
    public List<Event> applyLocalSourceChange(String caseId, String branchId, long baseSeq,
                                              String wordId, String newRawHex,
                                              String newReceiveText, String actor, String note) {
        BranchState state = projector.state(branchId);
        RawWord original = state.words.get(wordId);
        if (original == null) {
            throw new IllegalArgumentException("unknown word: " + wordId);
        }
        String rawHex = newRawHex == null ? original.rawText() : newRawHex;
        String receiveText = newReceiveText == null ? original.receiveText() : newReceiveText;
        long now = System.currentTimeMillis();
        List<Store.NewEvent> batch = new ArrayList<>();

        ObjectNode p = Json.obj();
        p.put("changeKind", "LOCAL_SOURCE_REPLACE_ONE_WORD");
        p.put("note", note);
        ArrayNode words = p.putArray("words");
        ObjectNode wo = words.addObject();
        wo.put("wordId", wordId);
        wo.put("seq", original.seq());
        wo.put("receiveText", receiveText);
        wo.put("channelId", original.channelId());
        wo.put("rawHex", rawHex);
        wo.put("origin", "local-source-change");
        ObjectNode before = wo.putObject("replaces");
        before.put("rawHex", original.rawText());
        before.put("receiveText", original.receiveText());
        batch.add(new Store.NewEvent(branchId + "-srcchange-" + (baseSeq + 1),
                EventType.SOURCE_BATCH_IMPORTED, now, p, basis(
                "one direct source changed on the same in-place sequence; original version "
                        + "remains readable in the earlier event",
                List.of("source:" + wordId, "channel:" + original.channelId()))));
        List<Event> committed = store.appendBatch(caseId, branchId, baseSeq, actor, batch);

        List<String> keys = List.of("source:" + wordId, "channel:" + original.channelId());
        invalidateByDependencyKeys(caseId, branchId, actor, keys,
                "local source change of " + wordId, committed.get(0).eventId());
        recordDerivedAnalyses(caseId, branchId, actor, projector.state(branchId).headSeq);
        return committed;
    }

    public ViewSnapshot snapshot(String branchId) {
        BranchState state = projector.state(branchId);
        DerivationEngine.Derived derived = projector.derive(state);
        return new ViewSnapshot(state, derived);
    }

    public ViewSnapshot snapshotAtEvent(String eventId) {
        BranchState state = projector.stateAtEvent(eventId);
        DerivationEngine.Derived derived = projector.derive(state);
        return new ViewSnapshot(state, derived);
    }

    public record ViewSnapshot(BranchState state, DerivationEngine.Derived derived) {
    }

    private JsonNode basis(String statement, List<String> dependencyKeys) {
        return ruleBasis(statement, dependencyKeys);
    }

    private static JsonNode ruleBasis(String statement) {
        return ruleBasis(statement, List.of());
    }

    private static JsonNode ruleBasis(String statement, List<String> dependencyKeys) {
        ObjectNode b = Json.obj();
        b.put("ruleVersion", "rules-1.0.0");
        b.put("statement", statement);
        ArrayNode deps = b.putArray("dependencyKeys");
        dependencyKeys.forEach(deps::add);
        ArrayNode rules = b.putArray("domainRules");
        rules.add("label octal presentation does not change underlying bits");
        rules.add("BNR/BCD/discrete interpretation is determined by dictionary version");
        rules.add("rate changes and acquisition gaps retain all legal segment candidates");
        rules.add("bad-parity words are kept but never enter aggregate values");
        return b;
    }
}
