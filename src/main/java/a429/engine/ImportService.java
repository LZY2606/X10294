package a429.engine;

import a429.events.EventType;
import a429.events.Payloads;
import a429.ids.Ids;
import a429.model.Dictionary;
import a429.store.Json;
import a429.store.Repository;

import java.util.List;

/**
 * Writes import and review operations as atomic event batches. Import failure rolls back the whole
 * transaction, so no half records ever remain. Source data is never rewritten: a mutation
 * supersedes the in-place word and records the prior value.
 */
public final class ImportService {
    private final Repository repository;
    private final StateService stateService;

    public ImportService(Repository repository, StateService stateService) {
        this.repository = repository;
        this.stateService = stateService;
    }

    public String importMaterial(String branchId, String label,
                                 Payloads.ImportMaterial material) {
        try {
            repository.database().connection().setAutoCommit(false);
            int seq = repository.maxEventSeq(branchId);
            long eventId;
            if (seq == 0 && repository.getBranch(branchId) == null) {
                repository.putBranch(new Repository.BranchRow(branchId, null, null,
                        material.dictionary().dictId(), material.dictionary().version(),
                        material.params().paramsId(), material.params().version(),
                        System.nanoTime(), label));
                repository.putDictionary(material.dictionary().dictId(),
                        material.dictionary().version(), Json.write(material.dictionary().defs()),
                        null);
                repository.putParams(material.params().paramsId(),
                        material.params().version(), material.params().toleranceRatio(),
                        material.params().gapFactor(), material.params().maxCandidates(), null);
                for (var channel : material.channels()) {
                    repository.upsertChannelConfig(channel.channel(), channel.bus(),
                            Json.write(channel.expectedLabels()));
                }
                eventId = repository.appendEvent(branchId, 1, EventType.IMPORT_MATERIAL,
                        Json.write(material), "initial import", System.nanoTime(), "[]");
            } else {
                eventId = repository.appendEvent(branchId, seq + 1, EventType.IMPORT_MATERIAL,
                        Json.write(material), "material import", System.nanoTime(), "[]");
            }
            for (Payloads.ImportedWord word : material.words()) {
                String uid = Ids.wordUid(word.seq(), word.tsNanos(), word.channel(), word.word());
                repository.insertRawWord(new Repository.RawWordRow(uid, branchId, word.seq(),
                        word.tsNanos(), word.channel(), word.word(), word.reversedCapture(),
                        eventId, null));
            }
            repository.database().connection().commit();
            return branchId;
        } catch (Exception e) {
            try {
                repository.database().connection().rollback();
            } catch (Exception ignored) {
                // rollback only
            }
            throw new IllegalStateException("import rolled back: " + e.getMessage(), e);
        } finally {
            try {
                repository.database().connection().setAutoCommit(true);
            } catch (Exception ignored) {
                // restore default
            }
        }
    }

    public String fork(String parentBranchId, int fromSeq, String childLabel) {
        Repository.BranchRow parent = repository.getBranch(parentBranchId);
        if (parent == null) {
            throw new IllegalArgumentException("unknown parent branch " + parentBranchId);
        }
        int head = repository.maxEventSeq(parentBranchId);
        if (fromSeq < 0 || fromSeq > head) {
            throw new IllegalArgumentException("fork point out of range");
        }
        String childId = Ids.of("br", parentBranchId + ":" + fromSeq + ":" + childLabel);
        try {
            repository.database().connection().setAutoCommit(false);
            repository.putBranch(new Repository.BranchRow(childId, parentBranchId, fromSeq,
                    parent.dictId(), parent.dictVersion(), parent.paramsId(),
                    parent.paramsVersion(), System.nanoTime(), childLabel));
            Payloads.ForkRef ref = new Payloads.ForkRef(parentBranchId, fromSeq, childId,
                    childLabel);
            repository.appendEvent(childId, fromSeq + 1, EventType.FORK, Json.write(ref),
                    "fork at seq " + fromSeq, System.nanoTime(), "[]");
            repository.database().connection().commit();
            return childId;
        } catch (Exception e) {
            try {
                repository.database().connection().rollback();
            } catch (Exception ignored) {
                // rollback only
            }
            throw e;
        } finally {
            try {
                repository.database().connection().setAutoCommit(true);
            } catch (Exception ignored) {
                // restore default
            }
        }
    }

    /**
     * Append a source mutation and an INVALIDATION event with the dependency-derived blast
     * radius. Only one direct source (one in-place word) changes.
     */
    public long mutateSource(String branchId, Payloads.SourceMutation mutation) {
        int head = repository.maxEventSeq(branchId);
        Snapshot before = stateService.snapshot(branchId);
        Snapshot.LiveWord target = before.words().get(mutation.seq());
        if (target == null) {
            throw new IllegalArgumentException("no in-place word at seq " + mutation.seq());
        }
        DerivationEngine.Derived derived = DerivationEngine.derive(before);
        var impacted = derived.graph().downstream(
                java.util.Set.of(target.wordUid()));
        Payloads.Invalidation invalidation = new Payloads.Invalidation(
                List.of(target.wordUid()), List.copyOf(impacted), mutation.kind());
        String edges = Json.write(derived.graph().edges().stream()
                .filter(e -> impacted.contains(e.from()) || impacted.contains(e.to()))
                .map(e -> java.util.Map.of("from", e.from(), "to", e.to(), "kind", e.kind()))
                .toList());
        try {
            repository.database().connection().setAutoCommit(false);
            long mutationEvent = repository.appendEvent(branchId, head + 1,
                    EventType.SOURCE_MUTATION, Json.write(mutation), mutation.note(),
                    System.nanoTime(), edges);
            repository.markSuperseded(branchId, mutation.seq(), mutationEvent);
            repository.insertRawWord(new Repository.RawWordRow(target.wordUid(), branchId,
                    mutation.seq(),
                    mutation.newTsNanos() != null ? mutation.newTsNanos()
                            : target.capture().tsNanos(),
                    target.capture().channel(), mutation.newWord(), mutation.reversedCapture(),
                    target.firstEventId(), null));
            repository.appendEvent(branchId, head + 2, EventType.INVALIDATION,
                    Json.write(invalidation), "dependency blast radius for " + mutation.kind(),
                    System.nanoTime(), edges);
            repository.database().connection().commit();
            return mutationEvent;
        } catch (Exception e) {
            try {
                repository.database().connection().rollback();
            } catch (Exception ignored) {
                // rollback only
            }
            throw e;
        } finally {
            try {
                repository.database().connection().setAutoCommit(true);
            } catch (Exception ignored) {
                // restore default
            }
        }
    }

    public long reviseParams(String branchId, Payloads.Revision revision) {
        int head = repository.maxEventSeq(branchId);
        repository.putParams(revision.params().paramsId(), revision.params().version(),
                revision.params().toleranceRatio(), revision.params().gapFactor(),
                revision.params().maxCandidates(), null);
        long eventId = stateService.append(branchId, EventType.PARAM_REVISION, revision,
                "parameter revision " + revision.fromVersion() + " -> "
                        + revision.toVersion(), head, "[]");
        invalidateRateScopes(branchId, eventId,
                DerivationEngine.paramsNodeId(revision.params().paramsId(),
                        revision.fromVersion()),
                "PARAM_REVISION");
        return eventId;
    }

    public long reviseDictionary(String branchId, Payloads.DictRevision revision) {
        int head = repository.maxEventSeq(branchId);
        repository.putDictionary(revision.dictionary().dictId(),
                revision.dictionary().version(), Json.write(revision.dictionary().defs()), null);
        long eventId = stateService.append(branchId, EventType.DICT_REVISION, revision,
                "dictionary revision " + revision.fromVersion() + " -> "
                        + revision.dictionary().version(), head, "[]");
        invalidateRateScopes(branchId, eventId,
                DerivationEngine.dictNodeId(revision.dictionary().dictId(),
                        revision.fromVersion()),
                "DICT_REVISION");
        return eventId;
    }

    private void invalidateRateScopes(String branchId, long revisionEventId,
                                      String previousNodeId, String reason) {
        Snapshot snapshot = stateService.snapshot(branchId);
        DerivationEngine.Derived derived = DerivationEngine.derive(snapshot);
        var impacted = derived.graph().downstream(java.util.Set.of(previousNodeId));
        Payloads.Invalidation invalidation = new Payloads.Invalidation(
                List.of(previousNodeId), List.copyOf(impacted), reason);
        repository.appendEvent(branchId, stateService.headSeq(branchId) + 1,
                EventType.INVALIDATION, Json.write(invalidation),
                "dependency blast radius for " + reason, System.nanoTime(), "[]");
    }

    /** Accept/reject a candidate. Optimistic: expectedHeadSeq must equal current head. */
    public long adjudicate(String branchId, Payloads.Adjudication adjudication) {
        long eventId = stateService.append(branchId, EventType.ADJUDICATE, adjudication,
                adjudication.decision() + " " + adjudication.scopeUid(),
                adjudication.expectedHeadSeq(), "[]");
        repository.putAdjudication(branchId, adjudication.scopeUid(), eventId,
                adjudication.decision());
        return eventId;
    }

    public long revoke(String branchId, String scopeUid, int expectedHeadSeq) {
        int head = repository.maxEventSeq(branchId);
        if (head != expectedHeadSeq) {
            throw new ConflictException(branchId, expectedHeadSeq, head);
        }
        Payloads.Adjudication payload = new Payloads.Adjudication(scopeUid, "REVOKED", null,
                head);
        long eventId = repository.appendEvent(branchId, head + 1, EventType.ADJUDICATION_REVOKE,
                Json.write(payload), "revoke adjudication " + scopeUid, System.nanoTime(), "[]");
        repository.revokeAdjudication(branchId, scopeUid, eventId);
        return eventId;
    }
}
