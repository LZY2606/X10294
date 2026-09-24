package a429.events;

import a429.casepack.CasePack;
import a429.decode.DecodeResult;
import a429.decode.Decoder;
import a429.decode.EngineeringResult;
import a429.decode.Interpreter;
import a429.domain.ChannelConfig;
import a429.domain.Dictionary;
import a429.domain.RawWord;
import a429.domain.WordFields;
import a429.rate.Interval;
import a429.rate.RateAnalysis;
import a429.rate.RateCandidate;
import a429.rate.RateSegmenter;
import a429.store.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the visible review tables from a fold snapshot. Every emitted row
 * carries a stable id, its source event and the dependency versions used.
 */
public final class ViewProjector {

    private final Decoder decoder = new Decoder();
    private final Interpreter interpreter = new Interpreter();
    private final RateSegmenter segmenter = new RateSegmenter();

    public FoldView project(CasePack pack, FoldState state, List<Event> events) {
        Map<String, ChannelConfig> channels = new LinkedHashMap<>();
        pack.channels().forEach(c -> channels.put(c.channelId(), c));

        ArrayNode words = Json.MAPPER.createArrayNode();
        ArrayNode engineering = Json.MAPPER.createArrayNode();
        List<RawWord> ordered = state.activeWords.values().stream()
                .sorted((a, b) -> a.channelId().equals(b.channelId())
                        ? Long.compare(a.receivedAtNs(), b.receivedAtNs())
                        : a.channelId().compareTo(b.channelId()))
                .toList();
        for (RawWord w : ordered) {
            ChannelConfig ch = channels.get(w.channelId());
            DecodeResult decoded = decoder.decode(w.wireHex(), ch);
            DecodeResult alternate = decoder.decodeAlternate(w.wireHex(), ch);
            String dictVersion = state.activeDictByChannel.getOrDefault(w.channelId(), pack.defaultDictVersion());
            Dictionary dict = pack.dictionaries().get(dictVersion);
            EngineeringResult eng = interpreter.interpret(decoded, dict);
            words.add(wordRow(w, ch, decoded, alternate, dictVersion));
            engineering.add(engRow(w, decoded.fields().labelOctal(), dictVersion, eng, ch));
        }

        ObjectNode rates = Json.MAPPER.createObjectNode();
        ArrayNode rateList = rates.putArray("keys");
        Map<String, List<RawWord>> byKey = groupByKey(ordered, channels);
        for (var e : byKey.entrySet()) {
            var parts = e.getKey().split("\\|");
            RateAnalysis analysis = segmenter.analyze(
                    rateKeyId(pack.caseId(), parts[0], parts[1]),
                    parts[0], parts[1], e.getValue(),
                    state.gapFactor, state.switchRatio, state.selectedCandidate);
            rateList.add(rateNode(pack.caseId(), analysis, events));
        }

        ArrayNode aggregates = aggregates(ordered, channels, pack, state.activeDictByChannel, events);
        ArrayNode decisionArray = Json.MAPPER.createArrayNode();
        for (Event ev : events) {
            if (ev.type().equals("CANDIDATE_ADJUDICATED") || ev.type().equals("DECISION_REVOKED")) {
                decisionArray.add(Json.MAPPER.valueToTree(Map.of(
                        "eventId", ev.eventId(),
                        "seq", ev.seq(),
                        "type", ev.type(),
                        "subjectId", ev.payload().path("subjectId").asText(""),
                        "accepted", ev.payload().path("accepted").asBoolean(false),
                        "revision", ev.revision())));
            }
        }
        ArrayNode patches = Json.MAPPER.createArrayNode();
        for (Event ev : events) {
            if (ev.type().equals("SOURCE_PATCHED")) {
                patches.add(Json.MAPPER.valueToTree(Map.of(
                        "eventId", ev.eventId(), "seq", ev.seq(),
                        "patchId", ev.payload().path("patchId").asText(""),
                        "wordId", ev.payload().path("wordId").asText(""),
                        "changes", ev.payload().path("changes").asText(""))));
            }
        }

        return new FoldView(state.headSeq, state.lastEventId, state.gapFactor, state.switchRatio,
                new LinkedHashMap<>(state.activeDictByChannel), words, engineering,
                aggregates, rates, events, decisionArray, patches);
    }

    public static String rateKeyId(String caseId, String channelId, String label) {
        return caseId + ":rate:" + channelId + ":" + label;
    }

    private Map<String, List<RawWord>> groupByKey(List<RawWord> words, Map<String, ChannelConfig> ch) {
        Map<String, List<RawWord>> out = new LinkedHashMap<>();
        for (RawWord w : words) {
            DecodeResult d = decoder.decode(w.wireHex(), ch.get(w.channelId()));
            String key = w.channelId() + "|" + d.fields().labelOctal();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
        }
        out.entrySet().removeIf(en -> en.getValue().size() < 2);
        out.values().forEach(l -> l.sort((a, b) -> Long.compare(a.receivedAtNs(), b.receivedAtNs())));
        return out;
    }

    private ObjectNode wordRow(RawWord w, ChannelConfig ch, DecodeResult decoded,
                               DecodeResult alternate, String dictVersion) {
        WordFields f = decoded.fields();
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", w.wordId());
        n.put("channelId", w.channelId());
        n.put("channelName", ch.name());
        n.put("position", w.position());
        n.put("receivedAtNs", w.receivedAtNs());
        n.put("receivedAtIso", w.receivedAtIso());
        n.put("wireHex", f.wireHex());
        n.put("bitOrder", f.bitOrder() + "(" + (ch.bitReversed() ? "capture-reversed" : "capture-straight") + ")");
        n.put("labelFieldRaw", String.format("%02X", f.labelFieldRaw()));
        n.put("labelOctal", f.labelOctal());
        n.put("sdi", f.sdi());
        n.put("ssm", f.ssm());
        n.put("ssmBinary", String.format("%2s", Integer.toBinaryString(f.ssm())).replace(' ', '0'));
        n.put("dataHex", f.dataHex());
        n.put("parityBit", f.parityOdd() ? 1 : 0);
        n.put("parityResult", f.parityValid() ? "odd-ok" : "odd-bad");
        n.put("parityValid", f.parityValid());
        n.put("dictVersion", dictVersion);
        n.put("fragmentId", w.fragmentId());
        ObjectNode alt = n.putObject("alternateOrder");
        alt.put("bitOrder", alternate.bitOrder());
        alt.put("labelOctal", alternate.fields().labelOctal());
        alt.put("parityValid", alternate.fields().parityValid());
        alt.put("labelFieldRaw", String.format("%02X", alternate.fields().labelFieldRaw()));
        n.put("configOrder", decoded.configOrder());
        return n;
    }

    private ObjectNode engRow(RawWord w, String labelOctal, String dictVersion,
                              EngineeringResult eng, ChannelConfig ch) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", w.wordId() + ":eng@" + dictVersion);
        n.put("wordId", w.wordId());
        n.put("channelId", w.channelId());
        n.put("labelOctal", labelOctal);
        n.put("dictVersion", dictVersion);
        n.put("entryName", eng.entryName());
        n.put("encoding", eng.encoding());
        ObjectNode q = n.putObject("quantity");
        q.put("value", eng.quantity().valid() ? eng.quantity().value() : null);
        q.put("unit", eng.quantity().unit());
        q.put("basis", eng.quantity().basis());
        q.put("valid", eng.quantity().valid());
        q.put("diagnostic", eng.quantity().diagnostic());
        n.put("ssmMeaning", eng.ssmMeaning());
        n.put("ssmInvalid", eng.ssmInvalid());
        n.put("parityValid", eng.parityValid());
        n.put("entersAggregate", eng.entersAggregate());
        ArrayNode states = n.putArray("discreteStates");
        eng.discreteStates().forEach(states::add);
        n.put("dataHex", String.format("%05X", eng.raw().get("dataBits")));
        ObjectNode provenance = n.putObject("provenance");
        provenance.put("wordId", w.wordId());
        provenance.put("wireHex", w.wireHex());
        provenance.put("channelId", ch.channelId());
        provenance.put("dictVersion", dictVersion);
        provenance.put("bitOrder", ch.bitReversed() ? "reversed-capture->config" : "straight");
        return n;
    }

    private ObjectNode rateNode(String caseId, RateAnalysis a, List<Event> events) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", a.keyId());
        n.put("channelId", a.channelId());
        n.put("labelOctal", a.labelOctal());
        n.put("gapFactor", a.gapFactor());
        n.put("switchRatio", a.switchRatio());
        ArrayNode intervals = n.putArray("intervals");
        for (Interval i : a.intervals()) {
            ObjectNode in = Json.MAPPER.createObjectNode();
            in.put("id", i.id());
            in.put("fromPosition", i.fromPosition());
            in.put("toPosition", i.toPosition());
            in.put("gapMs", round(i.gapMs()));
            intervals.add(in);
        }
        ArrayNode candidates = n.putArray("candidates");
        for (RateCandidate c : a.candidates()) {
            ObjectNode cn = Json.MAPPER.createObjectNode();
            cn.put("id", c.id());
            cn.put("segmentIndex", c.segmentIndex());
            cn.put("fromPosition", c.fromPosition());
            cn.put("toPosition", c.toPosition());
            cn.put("rateHz", round(c.rateHz()));
            cn.put("periodMs", round(c.periodMs()));
            cn.put("reason", c.reason());
            cn.put("ambiguous", c.ambiguous());
            cn.put("gapAtEnd", c.gapAtEnd());
            cn.put("status", candidateStatus(events, c.id()));
            candidates.add(cn);
        }
        String selected = a.selectedCandidateId();
        if (selected == null) {
            Event latest = latestDecision(events, a.keyId());
            if (latest != null && latest.type().equals("CANDIDATE_ADJUDICATED")) {
                selected = latest.payload().path("candidateId").asText(null);
            }
        }
        n.put("selectedCandidateId", selected);
        n.put("ambiguous", a.candidates().stream().anyMatch(RateCandidate::ambiguous));
        return n;
    }

    private String candidateStatus(List<Event> events, String candidateId) {
        String status = "proposed";
        for (Event ev : events) {
            if ((ev.type().equals("CANDIDATE_ADJUDICATED") || ev.type().equals("DECISION_REVOKED"))
                    && candidateId.equals(ev.payload().path("candidateId").asText(""))) {
                status = ev.type().equals("DECISION_REVOKED") ? "proposed"
                        : ev.payload().path("accepted").asBoolean() ? "accepted" : "rejected";
            }
        }
        return status;
    }

    private Event latestDecision(List<Event> events, String keyId) {
        Event latest = null;
        for (Event ev : events) {
            if ((ev.type().equals("CANDIDATE_ADJUDICATED") || ev.type().equals("DECISION_REVOKED"))
                    && ev.payload().path("subjectId").asText("").equals(keyId)) {
                latest = ev;
            }
        }
        return latest;
    }

    private ArrayNode aggregates(List<RawWord> words, Map<String, ChannelConfig> channels,
                                 CasePack pack, Map<String, String> dictByChannel, List<Event> events) {
        Map<String, List<EngineeringResult>> buckets = new LinkedHashMap<>();
        Map<String, Map<EngineeringResult, RawWord>> sourceOf = new LinkedHashMap<>();
        for (RawWord w : words) {
            ChannelConfig ch = channels.get(w.channelId());
            DecodeResult d = decoder.decode(w.wireHex(), ch);
            String dv = dictByChannel.getOrDefault(w.channelId(), pack.defaultDictVersion());
            EngineeringResult eng = interpreter.interpret(d, pack.dictionaries().get(dv));
            String key = w.channelId() + "|" + d.fields().labelOctal() + "|" + dv;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(eng);
            sourceOf.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(eng, w);
        }
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (var e : buckets.entrySet()) {
            List<EngineeringResult> included = e.getValue().stream()
                    .filter(EngineeringResult::entersAggregate).toList();
            if (included.isEmpty()) {
                continue;
            }
            var parts = e.getKey().split("\\|");
            double mean = included.stream().mapToDouble(x -> x.quantity().value()).average().orElse(Double.NaN);
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.put("id", pack.caseId() + ":agg:" + parts[0] + ":" + parts[1] + "@" + parts[2]);
            n.put("channelId", parts[0]);
            n.put("labelOctal", parts[1]);
            n.put("dictVersion", parts[2]);
            ObjectNode q = n.putObject("mean");
            q.put("value", round(mean));
            q.put("unit", included.get(0).quantity().unit());
            q.put("basis", included.get(0).quantity().basis());
            n.put("includedCount", included.size());
            ArrayNode excluded = n.putArray("excludedWords");
            e.getValue().stream().filter(x -> !x.entersAggregate())
                    .forEach(x -> {
                        ObjectNode xn = Json.MAPPER.createObjectNode();
                        xn.put("wordId", sourceOf.get(e.getKey()).get(x).wordId());
                        xn.put("wireHex", (String) x.raw().get("wireHex"));
                        xn.put("reason", !x.parityValid() ? "bad-parity"
                                : x.ssmInvalid() ? "ssm-invalid" : "unconvertible");
                        excluded.add(xn);
                    });
            out.add(n);
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 1000.0d) / 1000.0d;
    }

    static String fmtHex32(int word) {
        return String.format("%08X", word);
    }
}
