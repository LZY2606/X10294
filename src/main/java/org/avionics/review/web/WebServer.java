package org.avionics.review.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.avionics.review.casepack.CasePack;
import org.avionics.review.casepack.CasePackService;
import org.avionics.review.casepack.Invalidations;
import org.avionics.review.domain.AggregateValue;
import org.avionics.review.domain.Derivation;
import org.avionics.review.domain.EngineeringValue;
import org.avionics.review.domain.EventRecord;
import org.avionics.review.domain.Interval;
import org.avionics.review.domain.Invalidation;
import org.avionics.review.domain.RateCandidate;
import org.avionics.review.domain.RateSegment;
import org.avionics.review.domain.WordField;
import org.avionics.review.store.Database;
import org.avionics.review.store.EventReplayer;
import org.avionics.review.store.State;
import org.avionics.review.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebServer {

    private final ObjectMapper mapper;
    private final Store store;
    private final CasePackService casePackService;
    private Javalin app;

    public WebServer(ObjectMapper mapper, Store store) {
        this.mapper = mapper;
        this.store = store;
        this.casePackService = new CasePackService(mapper);
    }

    public Javalin start(String host, int port) {
        app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/web");
        });
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "UP", "rules", Store.RULE_VERSION)));
        app.get("/api/casepack", this::casepackMeta);
        app.post("/api/casepack/load", this::loadCasePack);
        app.get("/api/branches", ctx -> ctx.json(Map.of("branches", store.listBranches())));
        app.post("/api/branches", this::forkBranch);
        app.get("/api/events", this::listEvents);
        app.post("/api/events", this::appendEvent);
        app.post("/api/decisions", this::addDecision);
        app.post("/api/decisions/undo", this::undoDecision);
        app.get("/api/state", this::stateView);
        app.post("/api/probes/{id}/run", this::runProbe);
        app.get("/api/svg/timeline", this::svgTimeline);
        app.get("/api/archive/export", this::exportArchive);
        app.post("/api/archive/import", this::importArchive);
        app.exception(Store.ConflictException.class, (ex, ctx) -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("type", "VERSION_CONFLICT");
            body.put("expectedHead", ex.expectedHead);
            body.put("actualHead", ex.actualHead);
            ArrayNode unseen = body.putArray("unseenEvents");
            for (EventRecord ev : ex.unseen) {
                ObjectNode e = unseen.addObject();
                e.put("eventId", ev.eventId());
                e.put("seq", ev.seq());
                e.put("type", ev.type());
                e.set("payload", ev.payload());
            }
            ctx.status(409).json(body);
        });
        app.exception(IllegalArgumentException.class, (ex, ctx) -> {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        });
        app.start(host, port);
        return app;
    }

    private void casepackMeta(Context ctx) throws Exception {
        CasePack pack = casePackService.load();
        ObjectNode body = mapper.createObjectNode();
        body.put("packId", pack.packId());
        body.put("title", pack.title());
        body.put("initialDictVersion", pack.initialDictVersion());
        body.set("channels", mapper.valueToTree(pack.channels()));
        body.set("dictionaries", mapper.valueToTree(pack.dictionaries()));
        ArrayNode probes = body.putArray("probes");
        for (CasePack.ProbeDto p : pack.probes()) {
            ObjectNode pn = probes.addObject();
            pn.put("id", p.id());
            pn.put("kind", p.kind());
            pn.put("title", p.title());
            pn.put("description", p.description());
        }
        body.set("judgements", mapper.valueToTree(pack.judgements()));
        ctx.json(body);
    }

    private void loadCasePack(Context ctx) throws Exception {
        JsonNode body = ctx.body().isBlank() ? mapper.createObjectNode()
                : mapper.readTree(ctx.body());
        String expectedHead = body.path("expectedHead").asText(null);
        if (store.events("main").isEmpty()) {
            casePackService.importBaseline(store, casePackService.load(), expectedHead);
        }
        ctx.json(Map.of("status", "LOADED", "head", store.headEventId("main")));
    }

    private void forkBranch(Context ctx) throws Exception {
        JsonNode body = mapper.readTree(ctx.body());
        String name = body.get("name").asText();
        String parent = body.path("parent").asText("main");
        String after = body.path("afterEventId").asText(null);
        store.forkBranch(name, parent, after);
        ctx.status(201).json(Map.of("branch", name));
    }

    private void listEvents(Context ctx) {
        String branch = ctx.queryParamAsClass("branch", String.class).getOrDefault("main");
        List<EventRecord> events = store.events(branch);
        ArrayNode out = mapper.createArrayNode();
        for (EventRecord ev : events) {
            ObjectNode n = out.addObject();
            n.put("eventId", ev.eventId());
            n.put("seq", ev.seq());
            n.put("type", ev.type());
            n.put("ruleVersion", ev.ruleVersion());
            n.put("createdAt", ev.createdAt());
            n.set("payload", ev.payload());
            ArrayNode invs = n.putArray("invalidations");
            for (Invalidation inv : store.invalidations(ev.eventId())) {
                ObjectNode i = invs.addObject();
                i.put("scopeType", inv.scopeType());
                i.put("sid", inv.sid());
                i.put("reason", inv.reason());
            }
        }
        ctx.json(Map.of("branch", branch, "head", String.valueOf(store.headEventId(branch)),
                "events", out));
    }

    private void appendEvent(Context ctx) throws Exception {
        JsonNode body = mapper.readTree(ctx.body());
        String branch = body.path("branch").asText("main");
        String type = body.get("type").asText();
        JsonNode payload = body.get("payload");
        String expectedHead = body.path("expectedHead").asText(null);
        State before = EventReplayer.replay(store.events(branch));
        Derivation.Model beforeModel = new Derivation(before).derive();
        EventRecord ev = store.append(branch, type, payload, Store.RULE_VERSION, expectedHead, null);
        store.addInvalidations(ev.eventId(),
                Invalidations.forEvent(ev.eventId(), type, payload, beforeModel));
        ctx.status(201).json(Map.of("eventId", ev.eventId(), "seq", ev.seq()));
    }

    private void addDecision(Context ctx) throws Exception {
        JsonNode body = mapper.readTree(ctx.body());
        String branch = body.path("branch").asText("main");
        String expectedHead = body.path("expectedHead").asText(null);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("targetSid", body.get("targetSid").asText());
        payload.put("action", body.get("action").asText());
        if (body.has("chosenSid")) {
            payload.put("chosenSid", body.get("chosenSid").asText());
        }
        if (body.has("params")) {
            payload.set("params", body.get("params"));
        }
        payload.put("note", body.path("note").asText(""));
        payload.put("evidence", body.path("evidence").asText("MANUAL_REVIEW"));
        State before = EventReplayer.replay(store.events(branch));
        Derivation.Model beforeModel = new Derivation(before).derive();
        EventRecord ev = store.append(branch, "DECISION", payload, Store.RULE_VERSION, expectedHead, null);
        store.addInvalidations(ev.eventId(),
                Invalidations.forEvent(ev.eventId(), "DECISION", payload, beforeModel));
        ctx.status(201).json(Map.of("eventId", ev.eventId()));
    }

    private void undoDecision(Context ctx) throws Exception {
        JsonNode body = mapper.readTree(ctx.body());
        String branch = body.path("branch").asText("main");
        String expectedHead = body.path("expectedHead").asText(null);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("decisionEventId", body.get("decisionEventId").asText());
        State before = EventReplayer.replay(store.events(branch));
        Derivation.Model beforeModel = new Derivation(before).derive();
        EventRecord ev = store.append(branch, "UNDO_DECISION", payload, Store.RULE_VERSION, expectedHead, null);
        store.addInvalidations(ev.eventId(),
                Invalidations.forEvent(ev.eventId(), "UNDO_DECISION", payload, beforeModel));
        ctx.status(201).json(Map.of("eventId", ev.eventId()));
    }

    private void runProbe(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        CasePack pack = casePackService.load();
        CasePackService.ProbeRun run = casePackService.runProbe(store, pack, id);
        ObjectNode out = mapper.createObjectNode();
        out.put("branch", run.branch());
        out.put("probeId", run.probeId());
        out.put("probeEventId", run.probeEventId());
        out.put("resultEventId", run.resultEventId());
        out.set("expectations", mapper.valueToTree(run.expectationResults()));
        ctx.json(out);
    }

    private void stateView(Context ctx) {
        String branch = ctx.queryParamAsClass("branch", String.class).getOrDefault("main");
        Long atSeq = ctx.queryParamAsClass("atSeq", Long.class).getOrDefault(null);
        List<EventRecord> events = store.events(branch);
        if (atSeq != null) {
            List<EventRecord> sliced = new ArrayList<>();
            for (EventRecord ev : events) {
                if (ev.seq() <= atSeq) {
                    sliced.add(ev);
                }
            }
            events = sliced;
        }
        State state = EventReplayer.replay(events);
        Derivation.Model model = new Derivation(state).derive();
        ctx.json(renderModel(branch, state, model, events));
    }

    private ObjectNode renderModel(String branch, State state, Derivation.Model model,
                                   List<EventRecord> events) {
        ObjectNode root = mapper.createObjectNode();
        root.put("branch", branch);
        root.put("ruleVersion", Store.RULE_VERSION);
        root.put("head", state.headEventId == null ? "" : state.headEventId);
        root.put("atEventSeq", events.isEmpty() ? null : events.get(events.size() - 1).seq());

        ObjectNode channels = root.putObject("channels");
        state.channels.forEach((name, cfg) -> channels.set(name, mapper.valueToTree(cfg)));
        ObjectNode dicts = root.putObject("dictionaries");
        state.dictById.forEach((id, dv) -> dicts.set(id, mapper.valueToTree(dv)));

        ObjectNode params = root.putObject("params");
        params.put("gapThresholdMs", state.gapThresholdMs);
        params.put("rateSwitchRatio", state.rateSwitchRatio);

        ArrayNode words = root.putArray("words");
        for (Derivation.WordRow row : model.wordRows()) {
            words.add(renderWord(row));
        }
        root.set("intervals", mapper.valueToTree(model.intervals()));
        root.set("rateCandidates", mapper.valueToTree(model.rateCandidates()));
        root.set("aggregates", mapper.valueToTree(model.aggregates()));
        root.set("decodeNotes", mapper.valueToTree(model.decodeNotes()));
        return root;
    }

    private ObjectNode renderWord(Derivation.WordRow row) {
        ObjectNode w = mapper.createObjectNode();
        w.set("word", mapper.valueToTree(row.word()));
        w.put("sid", row.word().sid());
        w.put("parityOk", row.parityOk());
        w.put("labelOctal", row.labelOctal());
        w.put("sdi", row.sdi());
        w.put("ssm", row.ssm());
        w.put("ssmName", row.ssmName());
        w.put("dataHex", row.dataHex());
        w.set("fields", mapper.valueToTree(row.fields()));
        ArrayNode evs = w.putArray("engineeringValues");
        for (EngineeringValue ev : row.engineeringValues()) {
            evs.add(mapper.valueToTree(ev));
        }
        w.set("marks", mapper.valueToTree(row.marks()));
        return w;
    }

    private void svgTimeline(Context ctx) {
        String branch = ctx.queryParamAsClass("branch", String.class).getOrDefault("main");
        String channel = ctx.queryParamAsClass("channel", String.class).getOrDefault("CH_A");
        State state = EventReplayer.replay(store.events(branch));
        Derivation.Model model = new Derivation(state).derive();
        var words = model.wordRows().stream()
                .map(Derivation.WordRow::word)
                .filter(w -> w.channel().equals(channel))
                .sorted((a, b) -> Double.compare(a.receiveTimeMs(), b.receiveTimeMs()))
                .toList();
        var ivs = model.intervals().stream().filter(i -> i.channel().equals(channel)).toList();
        ctx.contentType("image/svg+xml").result(
                SvgRenderer.timeline(channel, words, ivs, model.rateCandidates()));
    }

    private void exportArchive(Context ctx) throws Exception {
        ctx.contentType("application/json")
                .header("Content-Disposition", "attachment; filename=arinc429-archive.json")
                .json(store.exportArchive());
    }

    private void importArchive(Context ctx) throws Exception {
        JsonNode root = mapper.readTree(ctx.body());
        store.importArchive(root);
        ctx.status(201).json(Map.of("status", "IMPORTED",
                "branches", store.listBranches()));
    }
}
