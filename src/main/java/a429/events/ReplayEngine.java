package a429.events;

import a429.casepack.CasePack;
import a429.casepack.PackFragment;
import a429.casepack.PackWord;
import a429.casepack.SourcePatch;
import a429.domain.RawWord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

/**
 * Replays branch events from scratch. The CasePack is the static reference
 * (channel configs + dictionary versions); imported fragments and patches in
 * the event log describe the active source material at each point.
 */
public final class ReplayEngine {

    public FoldState foldTo(CasePack pack, List<Event> events, int targetSeq) {
        FoldState state = new FoldState(pack);
        for (Event event : events) {
            if (event.seq() > targetSeq) {
                break;
            }
            apply(state, event);
            state.lastEventId = event.eventId();
            state.headSeq = event.seq();
        }
        return state;
    }

    public void apply(FoldState state, Event event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case "FRAGMENT_IMPORTED" -> {
                String fragmentId = p.get("fragmentId").asText();
                PackFragment fragment = state.pack.fragments().stream()
                        .filter(f -> f.fragmentId().equals(fragmentId)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("missing fragment " + fragmentId));
                if (!p.has("words") || !p.get("words").isArray()) {
                    fragment.words().forEach(w -> state.activeWords.put(w.wordId(), state.toRaw(w)));
                } else {
                    for (JsonNode w : p.get("words")) {
                        state.activeWords.put(w.get("wordId").asText(),
                                new RawWord(w.get("wordId").asText(), w.get("channelId").asText(),
                                        w.get("position").asInt(), w.get("receivedAtNs").asLong(),
                                        w.get("receivedAtIso").asText(), w.get("wireHex").asText(),
                                        fragmentId));
                    }
                }
            }
            case "SOURCE_PATCHED" -> {
                String wordId = p.get("wordId").asText();
                RawWord cur = state.activeWords.get(wordId);
                if (cur == null) {
                    SourcePatch sp = state.pack.patches().stream()
                            .filter(x -> x.patchId().equals(p.get("patchId").asText())).findFirst()
                            .orElseThrow(() -> new IllegalStateException("missing patch"));
                    throw new IllegalStateException("patched word not active: " + sp.wordId());
                }
                String hex = p.path("wireHex").asText(cur.wireHex());
                long ns = p.has("receivedAtNs") && !p.get("receivedAtNs").isNull()
                        ? p.get("receivedAtNs").asLong() : cur.receivedAtNs();
                String iso = p.path("receivedAtIso").asText(cur.receivedAtIso());
                state.activeWords.put(wordId, new RawWord(cur.wordId(), cur.channelId(),
                        cur.position(), ns, iso, hex, p.path("fragmentId").asText(cur.fragmentId())));
            }
            case "RATE_PARAMS_REVISED" -> {
                if (p.has("gapFactor") && !p.get("gapFactor").isNull()) {
                    state.gapFactor = p.get("gapFactor").asDouble();
                }
                if (p.has("switchRatio") && !p.get("switchRatio").isNull()) {
                    state.switchRatio = p.get("switchRatio").asDouble();
                }
            }
            case "DICT_VERSION_SELECTED" ->
                    state.activeDictByChannel.put(p.get("channelId").asText(),
                            p.get("dictVersion").asText());
            case "CANDIDATE_ADJUDICATED", "DECISION_REVOKED" -> {
                if (p.has("selectedCandidateId")) {
                    JsonNode id = p.get("selectedCandidateId");
                    state.selectedCandidate = id.isNull() ? null : id.asText();
                }
            }
            case "BRANCH_FORKED", "CONFLICT_RESOLVED" -> {
                // meta events do not change visible derived conclusions
            }
            default -> throw new IllegalStateException("unknown event type " + event.type());
        }
        if (event.payload() instanceof ObjectNode on && on.has("activeWordsSnapshotCount")) {
            // no-op marker, retained for future snapshots
        }
    }

    public Optional<Event> findDecision(List<Event> events, String subjectId) {
        return events.stream()
                .filter(e -> (e.type().equals("CANDIDATE_ADJUDICATED") || e.type().equals("DECISION_REVOKED"))
                        && e.payload().path("subjectId").asText("").equals(subjectId))
                .reduce((a, b) -> b);
    }
}
