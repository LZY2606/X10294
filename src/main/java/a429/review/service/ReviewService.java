package a429.review.service;

import a429.review.casepack.CasePack;
import a429.review.casepack.CasePackLoader;
import a429.review.domain.Arinc429;
import a429.review.domain.Dictionary;
import a429.review.domain.Events;
import a429.review.domain.Ids;
import a429.review.state.BranchEngine;
import a429.review.state.BranchRef;
import a429.review.state.BranchState;
import a429.review.state.JsonCodecs;
import a429.review.state.Material;
import a429.review.state.RawWord;
import a429.review.store.Repository;
import a429.review.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service. The source (raw words, timestamps, channels, dictionary
 * blobs) and its interpretations are stored separately; import is atomic;
 * every visible state is a deterministic replay of the append-only event log.
 */
public final class ReviewService {

    private final Store store;
    private final Repository repo;

    public ReviewService(Store store) {
        this.store = store;
        this.repo = new Repository(store);
    }

    public Repository repo() {
        return repo;
    }

    public synchronized boolean hasMaterial() {
        return repo.hasMaterial();
    }

    public synchronized Map<String, Object> importCasePack(CasePack.Pack pack) {
        try {
            if (repo.hasMaterial()) {
                throw new IllegalStateException("material already imported; create a branch instead");
            }
            validate(pack);
            String materialId = Ids.materialId(pack.packId);
            List<RawWord> raws = new ArrayList<>();
            for (CasePack.WordInput w : pack.words) {
                long hex = Arinc429.parseWord(w.rawHex);
                long ns = CasePackLoader.parseTimeNs(w.timestamp);
                raws.add(new RawWord(materialId, w.ordinal, Arinc429.hex(hex), w.timestamp, ns, w.channel));
            }
            raws.sort(java.util.Comparator.comparingInt(RawWord::ordinal));

            for (CasePack.DictBlob d : pack.dictionaries) {
                repo.saveDictionary(d.version, JsonCodecs.MAPPER.writeValueAsString(d));
            }
            Material material = new Material(materialId, pack.packId, raws, List.of(), List.of());
            repo.saveMaterial(material, pack.packId);

            String baselineDict = pack.dictionaries.get(0).version;
            String baselineBranch = Ids.branchId(materialId, baselineDict, 0);
            repo.saveBranch(new BranchRef(baselineBranch, materialId, baselineDict, 1, "baseline import"));

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("materialId", materialId);
            detail.put("caseId", pack.packId);
            detail.put("title", pack.title);
            detail.put("timeBasis", pack.timeBasis);
            detail.put("wordCount", raws.size());
            detail.put("channels", pack.channels);
            detail.put("dictionaryVersions", pack.dictionaries.stream().map(d -> d.version).toList());
            detail.put("baselineBranch", baselineBranch);
            String inputs = pack.packId + "|" + raws.size();
            repo.append(Events.IMPORT, baselineBranch, List.of(), "avionics-data-group",
                    Events.hashInputs(inputs), JsonCodecs.MAPPER.writeValueAsString(detail));
            store.commit();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("materialId", materialId);
            out.put("baselineBranch", baselineBranch);
            out.put("wordCount", raws.size());
            return out;
        } catch (RuntimeException e) {
            store.rollback();
            throw e;
        } catch (Exception e) {
            store.rollback();
            throw new RuntimeException("import failed, transaction rolled back: " + e.getMessage(), e);
        }
    }

    private void validate(CasePack.Pack pack) {
        if (pack == null || pack.packId == null || pack.words == null || pack.words.isEmpty()) {
            throw new IllegalArgumentException("case pack needs packId and at least one word");
        }
        if (pack.dictionaries == null || pack.dictionaries.isEmpty()) {
            throw new IllegalArgumentException("case pack needs at least one dictionary version");
        }
        List<Integer> seen = new ArrayList<>();
        for (CasePack.WordInput w : pack.words) {
            Arinc429.parseWord(w.rawHex);
            CasePackLoader.parseTimeNs(w.timestamp);
            if (w.channel == null) {
                throw new IllegalArgumentException("word " + w.ordinal + " missing channel");
            }
            seen.add(w.ordinal);
        }
        if (seen.stream().distinct().count() != seen.size()) {
            throw new IllegalArgumentException("duplicate ordinal in in-place sequence");
        }
    }

    // ----------------------------------------------------------- dictionaries

    public synchronized Map<String, Dictionary> dictionaries() {
        Map<String, Dictionary> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : repo.dictionaries().entrySet()) {
            CasePack.DictBlob blob = JsonCodecs.MAPPER.convertValue(
                    JsonCodecs.MAPPER.readTree(en.getValue()), CasePack.DictBlob.class);
            out.put(en.getKey(), CasePackLoader.toDictionary(blob));
        }
        return out;
    }

    public synchronized CasePack.Pack currentPackShape() {
        return null;
    }

    // --------------------------------------------------------------- branches

    public synchronized List<BranchRef> branches() {
        return repo.branches();
    }

    /** Re-interpret the same material under another dictionary version:
     *  creates a new branch; old branch, decisions and raw bytes stay readable. */
    public synchronized Map<String, Object> fork(String dictionaryVersion, String reason, String actor) {
        try {
            Material material = repo.loadMaterial();
            if (material == null) {
                throw new IllegalStateException("no material");
            }
            if (!repo.dictionaries().containsKey(dictionaryVersion)) {
                throw new IllegalArgumentException("unknown dictionary version " + dictionaryVersion);
            }
            long seq = repo.allEvents().size() + 1L;
            String branchId = Ids.branchId(material.materialId(), dictionaryVersion, (int) seq);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("newBranchId", branchId);
            detail.put("dictionaryVersion", dictionaryVersion);
            detail.put("reason", reason);
            detail.put("rawMaterialUnchanged", material.materialId());
            Repository.StoredEvent ev = repo.append(Events.REINTERPRET, branchId,
                    List.of(branchId), actor, Events.hashInputs(material.materialId() + "|" + dictionaryVersion),
                    JsonCodecs.MAPPER.writeValueAsString(detail));
            repo.saveBranch(new BranchRef(branchId, material.materialId(), dictionaryVersion, ev.seq(), reason));
            store.commit();
            return Map.of("branchId", branchId, "atSeq", ev.seq());
        } catch (Exception e) {
            store.rollback();
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- replay

    /**
     * Reconstruct one branch's visible state. If {@code uptoSeq >= 0} only
     * events with seq &lt;= uptoSeq are applied, restoring that historical
     * point with the dictionary version and parameters in force then.
     * Source patches and injected words are themselves materialized as
     * SOURCE_PATCH / INJECTED events so historical replay includes them.
     */
    public synchronized BranchState state(String branchId, long uptoSeq) {
        Material material = repo.loadMaterial();
        if (material == null) {
            throw new IllegalStateException("no material imported");
        }
        BranchRef ref = repo.branches().stream()
                .filter(b -> b.branchId().equals(branchId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown branch " + branchId));

        List<Repository.StoredEvent> events = new ArrayList<>();
        for (Repository.StoredEvent e : repo.allEvents()) {
            if (uptoSeq >= 0 && e.seq() > uptoSeq) {
                continue;
            }
            if (applies(e, branchId)) {
                events.add(e);
            }
        }

        // material with all source revisions active up to uptoSeq
        List<state.SourcePatch> patches = new ArrayList<>();
        List<state.InjectedWord> injected = new ArrayList<>();
        int patchSeq = 0;
        for (Repository.StoredEvent e : events) {
            if (Events.SOURCE_PATCH.equals(e.type())) {
                var node = JsonCodecs.MAPPER.readTree(e.detailJson());
                int ordinal = node.get("ordinal").asInt();
                String newHex = node.get("newRawHex").asText();
                String uid = Ids.wordUid(material.materialId(), ordinal,
                        Arinc429.parseWord(rawHexAt(material, events, ordinal)));
                patches.add(new state.SourcePatch(uid, newHex, (int) e.seq(),
                        node.has("reason") ? node.get("reason").asText() : "replay"));
            }
        }
        Material effective = new Material(material.materialId(), material.caseId(),
                material.rawWords(), List.copyOf(patches), List.copyOf(injected));

        BranchEngine engine = new BranchEngine(dictionaries(), effective);
        BranchState s = engine.initial(branchId, ref.dictionaryVersion());

        // initial materialization from raw words (baseline)
        s = engine.materialize(s, ref.dictionaryVersion(), s.params(), s.decisions(),
                Integer.MAX_VALUE, s.invalidated(), new ArrayList<>());

        for (Repository.StoredEvent e : events) {
            s = switch (e.type()) {
                case Events.PARAMS_REVISED, Events.RATE_DECIDED,
                     Events.ENCODING_DECIDED, Events.DECISION_UNDONE ->
                        engine.apply(s, new BranchEngine.EventBean(e.seq(), e.type(), e.detailJson()));
                default -> s;
            };
        }
        return s;
    }

    private String rawHexAt(Material material, List<Repository.StoredEvent> events, int ordinal) {
        String hex = material.rawWords().stream().filter(r -> r.ordinal() == ordinal)
                .map(RawWord::rawHex).findFirst().orElseThrow();
        for (Repository.StoredEvent e : events) {
            if (Events.SOURCE_PATCH.equals(e.type())) {
                var node = JsonCodecs.MAPPER.readTree(e.detailJson());
                if (node.get("ordinal").asInt() == ordinal) {
                    hex = node.get("newRawHex").asText();
                }
            }
        }
        return hex;
    }

    private boolean applies(Repository.StoredEvent e, String branchId) {
        List<?> scope = JsonCodecs.MAPPER.readValue(e.scopeJson(), List.class);
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        for (Object o : scope) {
            if (branchId.equals(String.valueOf(o))) {
                return true;
            }
        }
        return false;
    }
