package a429.archive;

import a429.db.Json;
import a429.db.Store;
import a429.event.Event;
import a429.event.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Whole-case archive: the full event log plus branch metadata.
 * Reimport replays events with identical ids and per-branch seq, so stable
 * identities, ordering and provenance edges survive export -&gt; delete -&gt; import.
 */
public class ArchiveService {

    private final Store store;

    public ArchiveService(Store store) {
        this.store = store;
    }

    public byte[] exportCase(String caseId) {
        ObjectNode root = Json.obj();
        root.put("format", "a429-review-bench/archive");
        root.put("formatVersion", 1);
        root.put("caseId", caseId);
        root.put("title", store.caseTitle(caseId));
        root.put("exportedEpochMs", System.currentTimeMillis());

        ArrayNode branches = root.putArray("branches");
        for (Store.BranchRow b : store.branches(caseId)) {
            ObjectNode bo = branches.addObject();
            bo.put("branchId", b.branchId());
            bo.put("caseId", b.caseId());
            if (b.parentBranchId() != null) {
                bo.put("parentBranchId", b.parentBranchId());
            }
            if (b.forkedFromEventId() != null) {
                bo.put("forkedFromEventId", b.forkedFromEventId());
            }
            bo.put("headSeq", b.headSeq());
            if (b.note() != null) {
                bo.put("note", b.note());
            }
        }
        ArrayNode events = root.putArray("events");
        for (Event ev : store.events(caseId)) {
            ObjectNode eo = events.addObject();
            eo.put("eventId", ev.eventId());
            eo.put("caseId", ev.caseId());
            eo.put("branchId", ev.branchId());
            eo.put("seq", ev.seq());
            eo.put("type", ev.type());
            eo.put("createdEpochMs", ev.createdEpochMs());
            eo.put("actor", ev.actor());
            eo.set("payload", ev.payload());
            eo.set("basis", ev.basis());
        }
        try {
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize archive", e);
        }
    }

    /** Atomically import an archive; any failure rolls the whole transaction back. */
    public void importArchive(byte[] bytes) {
        try {
            JsonNode root = Json.MAPPER.readTree(bytes);
            String caseId = root.path("caseId").asText();
            String title = root.path("title").asText("imported case");
            if (caseId.isBlank()) {
                throw new IllegalArgumentException("archive missing caseId");
            }
            if (store.caseIds().contains(caseId)) {
                throw new IllegalArgumentException("case already present: " + caseId
                        + " (export/delete/import requires the local DB to be deleted)");
            }
            store.createCase(caseId, title, root.path("exportedEpochMs").asLong(System.currentTimeMillis()));
            for (JsonNode b : root.path("branches")) {
                store.ensureBranch(b.path("branchId").asText(), caseId,
                        textOrNull(b, "parentBranchId"), textOrNull(b, "forkedFromEventId"),
                        System.currentTimeMillis(), b.path("note").asText(""));
            }
            for (JsonNode e : root.path("events")) {
                Event ev = new Event(e.path("eventId").asText(), caseId,
                        e.path("branchId").asText(), e.path("seq").asLong(),
                        e.path("type").asText(), e.path("createdEpochMs").asLong(),
                        e.path("actor").asText("archive"),
                        e.path("payload"), e.path("basis"));
                store.insertInherited(ev);
            }
            // recompute heads from the log to keep ordering provenance exact
            for (Store.BranchRow b : store.branches(caseId)) {
                long maxSeq = 0;
                for (Event ev : store.eventsOnBranch(b.branchId())) {
                    maxSeq = Math.max(maxSeq, ev.seq());
                }
                store.setHeadSeqNoCommit(b.branchId(), maxSeq);
            }
            store.connection().commit();
        } catch (Exception e) {
            try {
                store.connection().rollback();
            } catch (Exception ignored) {
            }
            throw new IllegalArgumentException("archive import failed, all changes rolled back: "
                    + e.getMessage(), e);
        }
    }

    private String textOrNull(JsonNode node, String key) {
        return node.has(key) && !node.path(key).isNull() ? node.path(key).asText() : null;
    }
}
