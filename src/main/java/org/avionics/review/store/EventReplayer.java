package org.avionics.review.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.avionics.review.domain.ChannelConfig;
import org.avionics.review.domain.DictDef;
import org.avionics.review.domain.DictVersion;
import org.avionics.review.domain.EventRecord;
import org.avionics.review.domain.RawWord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reconstructs {@link State} by walking the events of a branch. */
public final class EventReplayer {

    private EventReplayer() {
    }

    public static State replay(List<EventRecord> events) {
        State state = new State();
        for (EventRecord ev : events) {
            apply(state, ev);
            state.appliedEventIds.add(ev.eventId());
            state.headEventId = ev.eventId();
        }
        return state;
    }

    public static void apply(State state, EventRecord ev) {
        JsonNode p = ev.payload();
        switch (ev.type()) {
            case "IMPORT" -> applyImport(state, ev, p);
            case "PARAM_CHANGE" -> applyParams(state, p);
            case "DICT_REVISE" -> applyDictRevise(state, p);
            case "SOURCE_PATCH" -> applySourcePatch(state, p, ev);
            case "DECISION" -> applyDecision(state, ev, p);
            case "UNDO_DECISION" -> applyUndo(state, p);
            case "PROBE" -> applyProbe(state, p, ev);
            case "PROBE_RESULT" -> {
            }
            default -> throw new IllegalStateException("unknown event type " + ev.type());
        }
    }

    private static void applyImport(State state, EventRecord ev, JsonNode p) {
        String importId = p.get("importId").asText();
        state.importId = importId;
        state.channels.clear();
        for (JsonNode ch : p.get("channels")) {
            state.channels.put(ch.get("channel").asText(), new ChannelConfig(
                    ch.get("channel").asText(),
                    ch.path("name").asText(ch.get("channel").asText()),
                    ch.path("expectedRateKhz").asDouble(0),
                    ch.path("bus").asText("")));
        }
        for (JsonNode d : p.get("dictionaries")) {
            state.addDictionary(parseDict(d));
        }
        for (JsonNode w : p.get("words")) {
            long seq = w.get("seq").asLong();
            RawWord word = new RawWord(
                    wordSid(importId, seq), importId, seq,
                    w.get("timeMs").asDouble(),
                    w.get("channel").asText(),
                    w.get("rawHex").asText(),
                    w.path("dictVersionAtImport").asText(p.get("initialDictVersion").asText("")),
                    null, null, null);
            state.putWord(word);
        }
    }

    private static void applyParams(State state, JsonNode p) {
        if (p.has("gapThresholdMs")) {
            state.gapThresholdMs = p.get("gapThresholdMs").asDouble();
        }
        if (p.has("rateSwitchRatio")) {
            state.rateSwitchRatio = p.get("rateSwitchRatio").asDouble();
        }
    }

    private static void applyDictRevise(State state, JsonNode p) {
        state.addDictionary(parseDict(p.get("dict")));
    }

    private static void applySourcePatch(State state, JsonNode p, EventRecord ev) {
        String importId = state.importId;
        long seq = p.get("seq").asLong();
        RawWord original = state.word(importId, seq);
        RawWord revised = new RawWord(
                wordSid(importId, seq) + ":rev@" + ev.seq(),
                importId, seq,
                p.has("timeMs") ? p.get("timeMs").asDouble() : original.receiveTimeMs(),
                p.has("channel") ? p.get("channel").asText() : original.channel(),
                p.get("rawHex").asText(),
                original.dictVersionAtImport(),
                p.path("revisionId").asText("rev-" + ev.eventId()),
                original.sid(),
                p.path("reason").asText("source patch"));
        state.putWord(revised);
    }

    private static void applyDecision(State state, EventRecord ev, JsonNode p) {
        state.decisions.add(new State.Decision(
                ev.eventId(),
                p.get("targetSid").asText(),
                p.get("action").asText(),
                p.path("chosenSid").asText(null),
                p.path("params").isMissingNode() ? null : p.get("params"),
                p.path("note").asText(""),
                ev.ruleVersion(),
                null));
    }

    private static void applyUndo(State state, JsonNode p) {
        String target = p.get("decisionEventId").asText();
        state.decisions.removeIf(d -> d.eventId().equals(target));
    }

    private static void applyProbe(State state, JsonNode p, EventRecord ev) {
        if (!p.has("wordHexOverrides") && !p.has("timeOverrides")) {
            return;
        }
        JsonNode hexes = p.get("wordHexOverrides");
        if (hexes != null) {
            hexes.fields().forEachRemaining(entry -> {
                long seq = Long.parseLong(entry.getKey().substring(entry.getKey().indexOf('#') + 1));
                RawWord original = state.word(state.importId, seq);
                RawWord probed = new RawWord(
                        wordSid(state.importId, seq) + ":probe@" + ev.seq(),
                        state.importId, seq, original.receiveTimeMs(), original.channel(),
                        entry.getValue().asText(), original.dictVersionAtImport(),
                        "probe-" + ev.eventId(), original.sid(),
                        p.path("kind").asText("probe"));
                state.putWord(probed);
            });
        }
        JsonNode times = p.get("timeOverrides");
        if (times != null) {
            times.fields().forEachRemaining(entry -> {
                long seq = Long.parseLong(entry.getKey().substring(entry.getKey().indexOf('#') + 1));
                RawWord original = state.word(state.importId, seq);
                RawWord shifted = new RawWord(
                        original.sid(), original.importId(), original.seq(),
                        entry.getValue().asDouble(), original.channel(), original.rawHex(),
                        original.dictVersionAtImport(), original.revisionId(),
                        original.originWordSid(), original.revisionReason());
                state.putWord(shifted);
            });
        }
    }

    public static DictVersion parseDict(JsonNode d) {
        List<DictDef> defs = new ArrayList<>();
        for (JsonNode defNode : d.get("defs")) {
            Map<Integer, String> discrete = new LinkedHashMap<>();
            JsonNode db = defNode.get("discreteBits");
            if (db != null) {
                db.fields().forEachRemaining(e -> discrete.put(Integer.parseInt(e.getKey()), e.getValue().asText()));
            }
            defs.add(new DictDef(
                    defNode.get("labelOctal").asText(),
                    defNode.path("name").asText(""),
                    defNode.get("encoding").asText(),
                    defNode.path("unit").asText(""),
                    defNode.path("range").asDouble(0),
                    defNode.get("msb").asInt(),
                    defNode.get("lsb").asInt(),
                    defNode.path("impliedDecimals").asInt(0),
                    discrete));
        }
        return new DictVersion(
                d.get("versionId").asText(),
                d.path("publishedAt").asText(Instant.EPOCH.toString()),
                d.path("unitSign").asText("SIGNED_SYMMETRIC"),
                d.path("coordinateBasis").asText("BODY_AXIS"),
                defs);
    }

    public static String wordSid(String importId, long seq) {
        return "w:" + importId + "#" + seq;
    }
}
