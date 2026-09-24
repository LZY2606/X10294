package org.avionics.review.casepack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.avionics.review.domain.Arinc429;
import org.avionics.review.domain.Derivation;
import org.avionics.review.domain.EngineeringValue;
import org.avionics.review.domain.EventRecord;
import org.avionics.review.domain.Interval;
import org.avionics.review.domain.RateCandidate;
import org.avionics.review.domain.RateSegment;
import org.avionics.review.domain.RawWord;
import org.avionics.review.store.EventReplayer;
import org.avionics.review.store.State;
import org.avionics.review.store.Store;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the fixed offline case pack and materializes it as events:
 * one baseline IMPORT on {@code main}, and one forked probe branch per
 * boundary condition, each carrying adopted and rejected results.
 */
public class CasePackService {

    public static final String RESOURCE = "/casepack/arinc429-word-decoder-bench.json";

    private final ObjectMapper mapper;

    public CasePackService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CasePack load() {
        try (InputStream in = CasePackService.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled case pack " + RESOURCE);
            }
            return mapper.readValue(in, CasePack.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read case pack", e);
        }
    }

    /** Writes the canonical JSON pack into resources (used by the generator main). */
    public void writeResource(CasePack pack, java.nio.file.Path path) throws Exception {
        ObjectMapper writer = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        java.nio.file.Files.createDirectories(path.getParent());
        writer.writeValue(path.toFile(), pack);
    }

    public ObjectNode importBaseline(Store store, CasePack pack, String expectedHead) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("importId", CasePackFactory.IMPORT_ID);
        payload.put("initialDictVersion", pack.initialDictVersion());
        payload.put("source", pack.packId());
        payload.put("title", pack.title());

        ArrayNode channels = payload.putArray("channels");
        for (CasePack.ChannelDto c : pack.channels()) {
            ObjectNode cn = channels.addObject();
            cn.put("channel", c.channel());
            cn.put("name", c.name());
            cn.put("expectedRateKhz", c.expectedRateKhz());
            cn.put("bus", c.bus());
        }
        ArrayNode dicts = payload.putArray("dictionaries");
        for (CasePack.DictDto d : pack.dictionaries()) {
            dicts.add(mapper.valueToTree(d));
        }
        ArrayNode words = payload.putArray("words");
        for (CasePack.WordDto w : pack.words()) {
            ObjectNode wn = words.addObject();
            wn.put("seq", w.seq());
            wn.put("timeMs", w.timeMs());
            wn.put("channel", w.channel());
            wn.put("rawHex", w.rawHex());
        }
        EventRecord ev = store.append("main", "IMPORT", payload, Store.RULE_VERSION,
                expectedHead, "evt:" + CasePackFactory.IMPORT_ID + ":import");
        ObjectNode out = mapper.createObjectNode();
        out.put("eventId", ev.eventId());
        out.put("branch", "main");
        return out;
    }

    public record ProbeRun(String branch, String probeId, String kind,
                           String probeEventId, String resultEventId,
                           List<ObjectNode> expectationResults) {
    }

    public ProbeRun runProbe(Store store, CasePack pack, String probeId) {
        CasePack.ProbeDto probe = pack.probes().stream()
                .filter(p -> p.id().equals(probeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown probe " + probeId));
        String head = store.headEventId("main");
        String branch = "probe/" + probeId;
        if (store.listBranches().contains(branch)) {
            return rerunExisting(store, probe, branch);
        }
        store.forkBranch(branch, "main", head);

        ObjectNode probePayload = mapper.createObjectNode();
        probePayload.put("kind", probe.kind());
        probePayload.put("title", probe.title());
        probePayload.put("description", probe.description());
        ObjectNode hex = probePayload.putObject("wordHexOverrides");
        probe.wordHexOverrides().forEach(hex::put);
        ObjectNode times = probePayload.putObject("timeOverrides");
        probe.timeOverrides().forEach(times::put);
        EventRecord probeEvent = store.append(branch, "PROBE", probePayload);

        List<EventRecord> events = store.events(branch);
        State state = EventReplayer.replay(events);
        Derivation.Model model = new Derivation(state).derive();

        List<ObjectNode> results = evaluate(probe, state, model);
        ObjectNode resultPayload = mapper.createObjectNode();
        resultPayload.put("probeId", probeId);
        resultPayload.put("kind", probe.kind());
        resultPayload.set("expectations", mapper.valueToTree(results));
        EventRecord resultEvent = store.append(branch, "PROBE_RESULT", resultPayload);
        return new ProbeRun(branch, probeId, probe.kind(), probeEvent.eventId(),
                resultEvent.eventId(), results);
    }

    private ProbeRun rerunExisting(Store store, CasePack.ProbeDto probe, String branch) {
        List<EventRecord> events = store.events(branch);
        EventRecord probeEvent = events.stream().filter(e -> e.type().equals("PROBE")).findFirst().orElseThrow();
        EventRecord resultEvent = events.stream().filter(e -> e.type().equals("PROBE_RESULT")).findFirst().orElseThrow();
        List<ObjectNode> saved = new ArrayList<>();
        resultEvent.payload().withArray("expectations")
                .forEach(n -> saved.add((ObjectNode) n.deepCopy()));
        return new ProbeRun(branch, probe.id(), probe.kind(), probeEvent.eventId(),
                resultEvent.eventId(), saved);
    }

    private List<ObjectNode> evaluate(CasePack.ProbeDto probe, State state, Derivation.Model model) {
        List<ObjectNode> out = new ArrayList<>();
        for (CasePack.ExpectationDto exp : probe.expectations()) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", exp.id());
            r.put("description", exp.description());

            List<String> adopted = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            List<String> matchedSids = collectSignals(model, exp.adoptedSignals());
            List<String> rejectedSids = collectRejectedSignals(model, exp);

            boolean affectedOk = checkPatterns(exp.affectedSidPatterns(), state, model, true);
            boolean preservedOk = checkPatterns(exp.mustPreserveSidPatterns(), state, model, false);
            boolean signalsOk = !exp.adoptedSignals().isEmpty() ? !matchedSids.isEmpty() : true;

            adopted.addAll(matchedSids);
            rejected.addAll(rejectedSids);
            r.put("status", (affectedOk && preservedOk && signalsOk) ? "PASS" : "FAIL");
            r.putArray("affectedSidPatterns").addAll(toArray(exp.affectedSidPatterns()));
            r.putArray("mustPreserveSidPatterns").addAll(toArray(exp.mustPreserveSidPatterns()));
            r.putArray("adoptedResults").addAll(toArray(adopted));
            r.putArray("rejectedResults").addAll(toArray(rejected));
            out.add(r);
        }
        return out;
    }

    private ArrayNode toArray(List<String> values) {
        ArrayNode a = mapper.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    private List<String> collectSignals(Derivation.Model model, List<String> signals) {
        List<String> hits = new ArrayList<>();
        for (Derivation.WordRow row : model.wordRows()) {
            for (EngineeringValue ev : row.engineeringValues()) {
                String text = ev.valueText() == null ? "" : ev.valueText();
                String status = String.join(",", ev.diagnostics());
                String all = text + "|" + status + "|" + ev.status();
                for (String s : signals) {
                    if (all.contains(s)) {
                        hits.add(ev.sid() + " => " + s + " [" + ev.status() + "]");
                    }
                }
            }
        }
        for (String note : model.decodeNotes()) {
            for (String s : signals) {
                if (note.contains(s)) {
                    hits.add("note:" + note);
                }
            }
        }
        for (RateCandidate rc : model.rateCandidates()) {
            for (String s : signals) {
                if (s.equals("RATE_CANDIDATE_BASIS") && rc.preferred()) {
                    hits.add(rc.sid() + " preferred");
                }
            }
        }
        return hits;
    }

    private List<String> collectRejectedSignals(Derivation.Model model,
                                                CasePack.ExpectationDto exp) {
        List<String> hits = new ArrayList<>();
        for (Derivation.WordRow row : model.wordRows()) {
            for (EngineeringValue ev : row.engineeringValues()) {
                if (!ev.status().equals("OK") && ev.sid().startsWith("ev:")) {
                    hits.add(ev.sid() + " [" + ev.status() + "]");
                }
            }
        }
        return hits;
    }

    private boolean checkPatterns(List<String> patterns, State state,
                                  Derivation.Model model, boolean expectAffected) {
        if (patterns.isEmpty()) {
            return true;
        }
        boolean all = true;
        for (String pattern : patterns) {
            if (!matchesAny(pattern, state, model)) {
                all = false;
            }
        }
        return all;
    }

    private boolean matchesAny(String pattern, State state, Derivation.Model model) {
        for (Derivation.WordRow row : model.wordRows()) {
            if (match(pattern, row.word().sid())) {
                return true;
            }
            for (var f : row.fields()) {
                if (match(pattern, f.sid())) {
                    return true;
                }
            }
            for (EngineeringValue ev : row.engineeringValues()) {
                if (match(pattern, ev.sid())) {
                    return true;
                }
            }
        }
        for (Interval iv : model.intervals()) {
            if (match(pattern, iv.sid())) {
                return true;
            }
        }
        for (RateCandidate rc : model.rateCandidates()) {
            if (match(pattern, rc.sid())) {
                return true;
            }
            for (RateSegment seg : rc.segments()) {
                if (match(pattern, seg.sid())) {
                    return true;
                }
            }
        }
        for (var agg : model.aggregates()) {
            if (match(pattern, agg.sid())) {
                return true;
            }
        }
        return false;
    }

    static boolean match(String pattern, String sid) {
        String[] pParts = pattern.split("\\*", -1);
        if (pParts.length == 1) {
            return pattern.equals(sid);
        }
        int idx = 0;
        for (int i = 0; i < pParts.length; i++) {
            String part = pParts[i];
            if (i == pParts.length - 1) {
                return sid.startsWith(part, idx);
            }
            int found = sid.indexOf(part, idx);
            if (found < 0) {
                return false;
            }
            idx = found + part.length();
        }
        return true;
    }
}
