package a429.engine;

import a429.events.Event;
import a429.events.EventType;
import a429.events.Payloads;
import a429.ids.Ids;
import a429.model.Dictionary;
import a429.model.LabelDef;
import a429.model.RateParams;
import a429.model.RawCapture;
import a429.store.Json;
import a429.store.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays append-only events into a material {@link Snapshot}. Raw words, timestamps, channels,
 * dictionary versions and source changes are all preserved: mutation supersedes rather than
 * rewrites, and fork keeps the parent history readable.
 */
public final class StateService {
    private final Repository repository;

    public StateService(Repository repository) {
        this.repository = repository;
    }

    public Snapshot snapshot(String branchId) {
        return snapshotAt(branchId, Integer.MAX_VALUE);
    }

    public Snapshot snapshotAt(String branchId, int maxSeq) {
        Repository.BranchRow branch = repository.getBranch(branchId);
        if (branch == null) {
            throw new IllegalArgumentException("unknown branch " + branchId);
        }
        Map<Integer, Snapshot.LiveWord> words = new LinkedHashMap<>();
        Map<String, Snapshot.Decision> adjudications = new LinkedHashMap<>();
        Dictionary dictionary = null;
        RateParams params = null;
        int headSeq = 0;

        List<Event> ownEvents = repository.listEvents(branchId);
        int startOwnSeq = 0;
        if (branch.parentBranchId() != null && branch.forkedFromSeq() != null) {
            Snapshot parent = snapshotAt(branch.parentBranchId(), branch.forkedFromSeq());
            words.putAll(parent.words());
            adjudications.putAll(parent.adjudications());
            dictionary = parent.dictionary();
            params = parent.params();
            startOwnSeq = branch.forkedFromSeq();
        }

        for (Event event : ownEvents) {
            if (event.seq() > maxSeq) {
                break;
            }
            headSeq = event.seq();
            applyEvent(event, branch, words, adjudications);
            switch (event.type()) {
                case IMPORT_MATERIAL -> {
                    Payloads.ImportMaterial payload =
                            Json.read(event.payloadJson(), Payloads.ImportMaterial.class);
                    dictionary = toDictionary(payload.dictionary());
                    params = payload.params().toModel();
                }
                case IMPORT_DICT -> {
                    Payloads.DictRef ref = Json.read(event.payloadJson(), Payloads.DictRef.class);
                    dictionary = toDictionary(ref);
                }
                case IMPORT_PARAMS, PARAM_REVISION -> {
                    Payloads.Revision rev = Json.read(event.payloadJson(), Payloads.Revision.class);
                    params = rev.params().toModel();
                }
                case DICT_REVISION -> {
                    Payloads.DictRevision rev =
                            Json.read(event.payloadJson(), Payloads.DictRevision.class);
                    dictionary = toDictionary(rev.dictionary());
                }
                default -> { }
            }
        }
        if (headSeq == 0) {
            headSeq = startOwnSeq;
        }
        return new Snapshot(branchId, headSeq, words, dictionary, params, adjudications);
    }

    private void applyEvent(Event event, Repository.BranchRow branch,
                            Map<Integer, Snapshot.LiveWord> words,
                            Map<String, Snapshot.Decision> adjudications) {
        switch (event.type()) {
            case IMPORT_MATERIAL -> {
                Payloads.ImportMaterial payload =
                        Json.read(event.payloadJson(), Payloads.ImportMaterial.class);
                for (Payloads.ImportedWord imported : payload.words()) {
                    String uid = Ids.wordUid(imported.seq(), imported.tsNanos(),
                            imported.channel(), imported.word());
                    words.put(imported.seq(), new Snapshot.LiveWord(
                            new RawCapture(imported.seq(), imported.tsNanos(),
                                    imported.channel(), imported.word()),
                            imported.reversedCapture(), uid, event.id(), null));
                }
            }
            case SOURCE_MUTATION -> {
                Payloads.SourceMutation mutation =
                        Json.read(event.payloadJson(), Payloads.SourceMutation.class);
                Snapshot.LiveWord previous = words.get(mutation.seq());
                if (previous != null) {
                    long ts = mutation.newTsNanos() != null
                            ? mutation.newTsNanos() : previous.capture().tsNanos();
                    words.put(mutation.seq(), new Snapshot.LiveWord(
                            new RawCapture(mutation.seq(), ts, previous.capture().channel(),
                                    mutation.newWord()),
                            mutation.reversedCapture(), previous.wordUid(),
                            previous.firstEventId(), event.id()));
                }
            }
            case ADJUDICATE -> {
                Payloads.Adjudication adjudication =
                        Json.read(event.payloadJson(), Payloads.Adjudication.class);
                adjudications.put(adjudication.scopeUid(), new Snapshot.Decision(
                        adjudication.scopeUid(), adjudication.decision(),
                        adjudication.targetCandidateUid(), event.seq(), false));
            }
            case ADJUDICATION_REVOKE -> {
                Payloads.Adjudication revocation =
                        Json.read(event.payloadJson(), Payloads.Adjudication.class);
                Snapshot.Decision current = adjudications.get(revocation.scopeUid());
                if (current != null) {
                    adjudications.put(revocation.scopeUid(), new Snapshot.Decision(
                            current.scopeUid(), "REVOKED", current.targetCandidateUid(),
                            event.seq(), true));
                }
            }
            default -> { }
        }
    }

    public static Dictionary toDictionary(Payloads.DictRef ref) {
        Map<String, LabelDef> defs = new LinkedHashMap<>();
        for (LabelDef def : ref.defs()) {
            defs.put(def.labelOctal(), def);
        }
        return new Dictionary(ref.dictId(), ref.version(), defs);
    }

    /** Append an event with optimistic concurrency: expectedHeadSeq must match current head. */
    public long append(String branchId, EventType type, Object payload, String rationale,
                       int expectedHeadSeq, String dependencyEdgesJson) {
        int current = repository.maxEventSeq(branchId);
        if (current != expectedHeadSeq) {
            throw new ConflictException(branchId, expectedHeadSeq, current);
        }
        return repository.appendEvent(branchId, current + 1, type, Json.write(payload),
                rationale, System.nanoTime(), dependencyEdgesJson);
    }

    public int headSeq(String branchId) {
        return repository.maxEventSeq(branchId);
    }

    public List<Event> events(String branchId) {
        return repository.listEvents(branchId);
    }

    public Repository repository() {
        return repository;
    }
}
